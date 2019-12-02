package beam.analysis

import beam.agentsim.events.{AgencyRevenueEvent, ChargingPlugInEvent, ChargingPlugOutEvent, LeavingParkingEvent, ModeChoiceEvent, ParkEvent, PathTraversalEvent, PersonCostEvent, PointProcessEvent, RefuelSessionEvent, ReplanningEvent, ReserveRideHailEvent, RideHailFleetStateEvent}
import beam.analysis.plots.GraphAnalysis
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import beam.utils.DummyEvent
import org.matsim.api.core.v01.events.{ActivityEndEvent, ActivityStartEvent, Event, GenericEvent, LinkEnterEvent, LinkLeaveEvent, PersonArrivalEvent, PersonDepartureEvent, PersonEntersVehicleEvent, PersonLeavesVehicleEvent, PersonMoneyEvent, PersonStuckEvent, TransitDriverStartsEvent, VehicleAbortsEvent, VehicleEntersTrafficEvent, VehicleLeavesTrafficEvent}
import org.matsim.contrib.bicycle.MotorizedInteractionEvent
import org.matsim.contrib.socnetsim.framework.events.CourtesyEvent
import org.matsim.core.api.experimental.events.{AgentWaitingForPtEvent, BoardingDeniedEvent, LaneEnterEvent, LaneLeaveEvent, TeleportationArrivalEvent, VehicleArrivesAtFacilityEvent, VehicleDepartsAtFacilityEvent}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.{LastEventOfIteration, LastEventOfSimStep}
import org.matsim.withinday.events

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class EventStatus(start: Double, end: Double, eventType: String, nextType: Option[String])
class RideHailFleetAnalysis(beamServices: BeamServices) extends GraphAnalysis {

  val resolutionInSeconds = 60
  val timeBins = 0 until 24*3600 by resolutionInSeconds

  val keys = Map("driving-full" -> 0,
    "driving-reposition" -> 1,
    "driving-topickup" -> 2,
    "driving-tocharger" -> 3,
    "queuing" -> 4,
    "charging" -> 5,
    "idle" -> 6,
    "offline" -> 7,
    "parked" -> 8)

  import RideHailFleetStateEvent._
  import RefuelSessionEvent._

  val rideHailCav = mutable.Map[String, ListBuffer[Event]]()
  val ridehailHumanDriven = mutable.Map[String, ListBuffer[Event]]()
  val cavSet = mutable.Set[String]()
  val ridehailVehicleSet = mutable.Set[String]()

  override def processStats(event: Event): Unit = {
    event match {
      case rideHailFleetStateEvents: RideHailFleetStateEvent =>
        beamServices.simMetricCollector.write(
          "VehiclesStates",
          SimulationTime(rideHailFleetStateEvents.getTime.toInt),
          Map(
            ATTRIBUTE_EV_CAV_COUNT -> rideHailFleetStateEvents.getAttributes.get(ATTRIBUTE_EV_CAV_COUNT).toDouble,
            ATTRIBUTE_NON_EV_CAV_COUNT -> rideHailFleetStateEvents.getAttributes.get(ATTRIBUTE_NON_EV_CAV_COUNT).toDouble,
            ATTRIBUTE_EV_NON_CAV_COUNT -> rideHailFleetStateEvents.getAttributes.get(ATTRIBUTE_EV_NON_CAV_COUNT).toDouble,
            ATTRIBUTE_NON_EV_NON_CAV_COUNT -> rideHailFleetStateEvents.getAttributes.get(ATTRIBUTE_NON_EV_NON_CAV_COUNT).toDouble
          ),
          tags = Map(
            "VehicleType" -> rideHailFleetStateEvents.getAttributes.get(RideHailFleetStateEvent.ATTRIBUTE_VEHICLE_TYPE)
          )
        )
      case refuelSessionEvent: RefuelSessionEvent =>
        if(refuelSessionEvent.getAttributes.get(ATTRIBUTE_ENERGY_DELIVERED).toDouble > 0.0){
          val vehicle = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
          if(rideHailCav.contains(vehicle)) {
            appendEvent(rideHailCav, refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5), vehicle)
          }
          if(ridehailHumanDriven.contains(vehicle)) {
            appendEvent(ridehailHumanDriven, refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5), vehicle)
          }
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if(pathTraversalEvent.mode == BeamMode.CAR) {
          val rideHail = pathTraversalEvent.vehicleId.toString.contains("rideHail")
          val cav = pathTraversalEvent.vehicleType.contains("L5")
          val vehicle = pathTraversalEvent.vehicleId.toString
          if(rideHail && cav) {
            appendEvent(rideHailCav, pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5), vehicle)
          }
          else if (rideHail && !cav){
            appendEvent(ridehailHumanDriven, pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5), vehicle)
          }
        }
      case parkEvent: ParkEvent =>
        val vehicle = parkEvent.vehicleId.toString
        if(rideHailCav.contains(vehicle)) {
          appendEvent(rideHailCav, parkEvent, vehicle)
        }
        if(ridehailHumanDriven.contains(vehicle)) {
          appendEvent(ridehailHumanDriven, parkEvent, vehicle)
        }
      case _ =>
    }
  }

  def appendEvent(vehicleEventTypeMap: mutable.Map[String, ListBuffer[Event]], event: Event, vehicle: String){
    val events: ListBuffer[Event] = vehicleEventTypeMap.getOrElse(vehicle, new ListBuffer[Event])
    events += event
    vehicleEventTypeMap(vehicle) = events
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    rideHailCav.values.foreach(assignVehicleDayToLocationMatrix(_, true, true))
    ridehailHumanDriven.values.foreach(assignVehicleDayToLocationMatrix(_, true, false))
  }

  override def resetStats(): Unit = {
    rideHailCav.clear()
    ridehailHumanDriven.clear()
  }

  def assignVehicleDayToLocationMatrix(days: ListBuffer[Event], isRH: Boolean, isCAV: Boolean): Unit = {
    val timeUtilization = Array.ofDim[Int](timeBins.size, keys.values.max + 1)
    val distanceUtilization = Array.ofDim[Int](timeBins.size, keys.values.max + 1)
    if(isRH) {
      if(isCAV)
        timeBins.indices.foreach(timeUtilization(_)(keys("idle")) += 1)
      else
        timeBins.indices.foreach(timeUtilization(_)(keys("offline")) += 1)
    }
    else {
      timeBins.indices.foreach(timeUtilization(_)(keys("parked")) += 1)
    }
    var chargingNext = false
    var pickupNext = false
    var chargingOneAfter = false
    days.zipWithIndex.foreach(eventIndex => {
      val event = eventIndex._1
      val idx = eventIndex._2
      val lastEvent = idx == days.size - 1
      if (lastEvent) {
        chargingNext = false
        pickupNext = false
      }
      else {
        val chargingDirectlyNext = days(idx + 1).getEventType == "RefuelSessionEvent"
        if (idx == days.size - 2)
          chargingOneAfter = false
        else {
          chargingOneAfter = days(idx + 1).getEventType == "ParkEvent" && days(idx + 2).getEventType == "RefuelSessionEvent"
          chargingNext = chargingDirectlyNext | chargingOneAfter
          pickupNext = days(idx + 1).getEventType == "PathTraversal" && days(idx + 1).getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt >= 1
        }
      }
      val eventCharacteristics = classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH, isCAV)
      val afterEventStart = timeBins.map(_ >= eventCharacteristics.start)
      val duringEvent = afterEventStart.zip(timeBins.map(_ < eventCharacteristics.end)).map(afterEventBin => afterEventBin._1 && afterEventBin._2)

      timeUtilization(afterEventStart) = 0.0
      timeUtilization(duringEvent)(keys(eventCharacteristics.eventType)) += 1.0

      event match {
        case event: PathTraversalEvent =>
          val sum = duringEvent.count(during => during)
          if(sum > 0) {
            val meanDistancePerTime = event.legLength / sum
            distanceUtilization(duringEvent)(keys(eventCharacteristics.eventType)) += meanDistancePerTime/1609.34
          }
          else {
            firstIndex = np.argmax(afterEventStart)
            distanceUtilization(firstIndex)(keys(eventCharacteristics.eventType)) += event.legLength/1609.34
          }
        case _ =>
      }

      eventCharacteristics.nextType.foreach(nextType => {
        afterEventEnd = timeBins.map(_ >= eventCharacteristics.end)
        timeUtilization(afterEventEnd)(keys(nextType)) += 1.0
      })
    })
  }

  def classifyEventLocation(event: Event, lastEvent: Boolean, chargingNext: Boolean, pickupNext: Boolean, isRH: Boolean, isCAV: Boolean): EventStatus = {
    event match {
      case event: PathTraversalEvent =>
        if (isRH) {
          if (event.numberOfPassengers >= 1) {
            if (lastEvent) {
              if(isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("offline"))
            }
            else {
              if(chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
            }
          }
          else {
            if (lastEvent) {
              if(isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("offline"))
            }
            else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
              else if (pickupNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
            }
          }
        }
        else {
          if (chargingNext)
            EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
          else {
            if (event.numberOfPassengers >= 1)
              EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
            else
              EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
          }
        }
      case event: RefuelSessionEvent =>
        val duration = event.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_SESSION_DURATION).toDouble
        if(isRH) {
          if(lastEvent){
            if(isCAV)
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
            else
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("offline"))
          }
          else
            EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
        }
        else {
          EventStatus(event.getTime, event.getTime + duration, "charging", Some("parked"))
        }
      case event: ParkEvent =>
        if(isRH)
          EventStatus(event.getTime, 30*3600, "idle", None)
        else
          EventStatus(event.getTime, 30*3600, "parked", None)
      case _ =>
        EventStatus(0.0, 0.0, "Unknown", None)
    }
  }
}
