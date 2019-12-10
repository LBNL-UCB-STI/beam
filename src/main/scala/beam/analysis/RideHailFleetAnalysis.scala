package beam.analysis

import beam.agentsim.events.{ParkEvent, PathTraversalEvent, RefuelSessionEvent, RideHailFleetStateEvent}
import beam.analysis.plots.GraphAnalysis
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.metrics.Metrics
import beam.sim.metrics.Metrics.MetricLevel
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class EventStatus(start: Double, end: Double, eventType: String, nextType: Option[String])

class RideHailFleetAnalysis(beamServices: BeamServices) extends GraphAnalysis {

  val resolutionInSeconds = 60
  val timeBins = 0 until 24 * 3600 by resolutionInSeconds

  val keys = Map(
    "driving-full"       -> 0,
    "driving-reposition" -> 1,
    "driving-topickup"   -> 2,
    "driving-tocharger"  -> 3,
    "queuing"            -> 4,
    "charging"           -> 5,
    "idle"               -> 6,
    "offline"            -> 7,
    "parked"             -> 8
  )

  import RideHailFleetStateEvent._
  import RefuelSessionEvent._

  val rideHailCav = mutable.Map[String, ListBuffer[Event]]()
  val ridehailHumanDriven = mutable.Map[String, ListBuffer[Event]]()
  val personalCav = mutable.Map[String, ListBuffer[Event]]()
  val personalHumanDriven = mutable.Map[String, ListBuffer[Event]]()

  var timeUtilizationRH_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var distanceUtilizationRH_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var timeUtilizationRH_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var distanceUtilizationRH_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var timeUtilizationPV_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var distanceUtilizationPV_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var timeUtilizationPV_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
  var distanceUtilizationPV_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)

  override def processStats(event: Event): Unit = {
    event match {
      case rideHailFleetStateEvents: RideHailFleetStateEvent =>
        beamServices.simMetricCollector.write(
          "VehiclesStates",
          SimulationTime(rideHailFleetStateEvents.getTime.toInt),
          Map(
            ATTRIBUTE_EV_CAV_COUNT -> rideHailFleetStateEvents.getAttributes.get(ATTRIBUTE_EV_CAV_COUNT).toDouble,
            ATTRIBUTE_NON_EV_CAV_COUNT -> rideHailFleetStateEvents.getAttributes
              .get(ATTRIBUTE_NON_EV_CAV_COUNT)
              .toDouble,
            ATTRIBUTE_EV_NON_CAV_COUNT -> rideHailFleetStateEvents.getAttributes
              .get(ATTRIBUTE_EV_NON_CAV_COUNT)
              .toDouble,
            ATTRIBUTE_NON_EV_NON_CAV_COUNT -> rideHailFleetStateEvents.getAttributes
              .get(ATTRIBUTE_NON_EV_NON_CAV_COUNT)
              .toDouble
          ),
          tags = Map(
            "VehicleType" -> rideHailFleetStateEvents.getAttributes.get(RideHailFleetStateEvent.ATTRIBUTE_VEHICLE_TYPE)
          )
        )
      case refuelSessionEvent: RefuelSessionEvent =>
        if (refuelSessionEvent.getAttributes.get(ATTRIBUTE_ENERGY_DELIVERED).toDouble > 0.0) {
          val vehicle = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
          val vehicleType = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_TYPE)
          val rideHail = vehicle.contains("rideHail")
          val cav = vehicleType.contains("L5")
          if (rideHail && cav) {
            val updatedEvent = refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5)
            val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_CAV, distanceUtilizationRH_CAV, updatedEvent, vehicle, true, true)
            timeUtilizationRH_CAV = timesDistancesUtilization._1
            distanceUtilizationRH_CAV = timesDistancesUtilization._2

          } else if (rideHail && !cav) {
            val updatedEvent = refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5)
            val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_noCAV, distanceUtilizationRH_noCAV, updatedEvent, vehicle, true, false)
            timeUtilizationRH_noCAV = timesDistancesUtilization._1
            distanceUtilizationRH_noCAV = timesDistancesUtilization._2

          } else if (!rideHail && cav) {
            val updatedEvent = refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5)
            val timesDistancesUtilization = processEvents(personalCav, timeUtilizationPV_CAV, distanceUtilizationPV_CAV, updatedEvent, vehicle, false, true)
            timeUtilizationPV_CAV = timesDistancesUtilization._1
            distanceUtilizationPV_CAV = timesDistancesUtilization._2

          } else if (!rideHail && !cav) {
            val updatedEvent = refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5)
            val timesDistancesUtilization = processEvents(personalHumanDriven, timeUtilizationPV_noCAV, distanceUtilizationPV_noCAV, updatedEvent, vehicle, false, false)
            timeUtilizationPV_noCAV = timesDistancesUtilization._1
            distanceUtilizationPV_noCAV = timesDistancesUtilization._2
          }
          processVehicleStats()
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if (pathTraversalEvent.mode == BeamMode.CAR) {
          val vehicle = pathTraversalEvent.vehicleId.toString
          val rideHail = vehicle.contains("rideHail")
          val cav = pathTraversalEvent.vehicleType.contains("L5")

          if (rideHail && cav) {
            val updatedEvent = pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5)
            val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_CAV, distanceUtilizationRH_CAV, updatedEvent, vehicle, true, true)
            timeUtilizationRH_CAV = timesDistancesUtilization._1
            distanceUtilizationRH_CAV = timesDistancesUtilization._2

          } else if (rideHail && !cav) {
            val updatedEvent = pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5)
            val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_noCAV, distanceUtilizationRH_noCAV, updatedEvent, vehicle, true, false)
            timeUtilizationRH_noCAV = timesDistancesUtilization._1
            distanceUtilizationRH_noCAV = timesDistancesUtilization._2

          } else if (!rideHail && cav) {
            val updatedEvent = pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5)
            val timesDistancesUtilization = processEvents(personalCav, timeUtilizationPV_CAV, distanceUtilizationPV_CAV, updatedEvent, vehicle, false, true)
            timeUtilizationPV_CAV = timesDistancesUtilization._1
            distanceUtilizationPV_CAV = timesDistancesUtilization._2

          } else if (!rideHail && !cav) {

            val updatedEvent = pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5)
            val timesDistancesUtilization = processEvents(personalHumanDriven, timeUtilizationPV_noCAV, distanceUtilizationPV_noCAV, updatedEvent, vehicle, false, false)
            timeUtilizationPV_noCAV = timesDistancesUtilization._1
            distanceUtilizationPV_noCAV = timesDistancesUtilization._2
          }
          processVehicleStats()
        }

      case parkEvent: ParkEvent =>
        val vehicle = parkEvent.vehicleId.toString
        if (rideHailCav.contains(vehicle)) {
          val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_CAV, distanceUtilizationRH_CAV, parkEvent, vehicle, true, true)
          timeUtilizationRH_CAV = timesDistancesUtilization._1
          distanceUtilizationRH_CAV = timesDistancesUtilization._2

        } else if (ridehailHumanDriven.contains(vehicle)) {
          val timesDistancesUtilization = processEvents(ridehailHumanDriven, timeUtilizationRH_noCAV, distanceUtilizationRH_noCAV,  parkEvent, vehicle, true, false)
          timeUtilizationRH_noCAV = timesDistancesUtilization._1
          distanceUtilizationRH_noCAV = timesDistancesUtilization._2

        } else if (personalCav.contains(vehicle)) {
          val timesDistancesUtilization = processEvents(personalCav, timeUtilizationPV_CAV, distanceUtilizationPV_CAV, parkEvent, vehicle, false, true)
          timeUtilizationPV_CAV = timesDistancesUtilization._1
          distanceUtilizationPV_CAV = timesDistancesUtilization._2

        } else if (personalHumanDriven.contains(vehicle)) {
          val timesDistancesUtilization = processEvents(personalHumanDriven, timeUtilizationPV_noCAV, distanceUtilizationPV_noCAV, parkEvent, vehicle, false, false)
          timeUtilizationPV_noCAV = timesDistancesUtilization._1
          distanceUtilizationPV_noCAV = timesDistancesUtilization._2
        }
        processVehicleStats()
      case _ =>
    }
  }

  def processEvents(vehicleStats: mutable.Map[String, ListBuffer[Event]],
                    times: Array[Array[Double]],
                    distance: Array[Array[Double]],
                    event: Event,
                    vehicle: String,
                    isRH: Boolean,
                    isCAV: Boolean
                   ): (Array[Array[Double]], Array[Array[Double]]) ={
    val now = getUpadatedEvent(vehicleStats, event, vehicle)
    val timesDistances = assignVehicleDayToLocationMatrix(now, isRH, isCAV)
    val timesUtilization = timesDistances._1.zip(times).map(time => time._1.zip(time._2).map(t => t._1 + t._2))
    val distanceUtilization = timesDistances._2.zip(distance).map(distance => distance._1.zip(distance._2).map(d => d._1 + d._2))
    (timesUtilization, distanceUtilization)
  }

  def getUpadatedEvent(vehicleEventTypeMap: mutable.Map[String, ListBuffer[Event]], event: Event, vehicle: String): ListBuffer[Event] = {
    val events: ListBuffer[Event] = vehicleEventTypeMap.getOrElse(vehicle, new ListBuffer[Event]())
    events += event
    vehicleEventTypeMap(vehicle) = events
    events
  }

  def processVehicleStats(): Unit ={
    val timeUtilizationRH_CAVsum =
      timeUtilizationRH_CAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val distanceUtilizationRH_CAVsum =
      distanceUtilizationRH_CAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val timeUtilizationRH_noCAVsum =
      timeUtilizationRH_noCAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val distanceUtilizationRH_noCAVsum =
      distanceUtilizationRH_noCAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val timeUtilizationPV_CAVsum =
      timeUtilizationPV_CAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val distanceUtilizationPV_CAVsum =
      distanceUtilizationPV_CAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val timeUtilizationPV_noCAVsum =
      timeUtilizationPV_noCAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })
    val distanceUtilizationPV_noCAVsum =
      distanceUtilizationPV_noCAV.reduce((xSeries1, xSeries2) => xSeries1.zip(xSeries2).map { case (x, y) => x + y })

    keys.foreach {
      case (key, idx) =>
        val tags = Map("vehicle-state" -> key)
        def write(metric: String, value: Double): Unit = {
          beamServices.simMetricCollector.writeGlobal(
            metric,
            value,
            Metrics.ShortLevel,
            tags
          )
        }
        write("bev-no-cav-time", timeUtilizationPV_noCAVsum(idx))
        write("bev-no-cav-distance", distanceUtilizationPV_noCAVsum(idx))
        write("bev-cav-time", timeUtilizationPV_CAVsum(idx))
        write("bev-cav-distance", distanceUtilizationPV_CAVsum(idx))
        write("rh-no-cav-time", timeUtilizationRH_noCAVsum(idx))
        write("rh-no-cav-distance", distanceUtilizationRH_noCAVsum(idx))
        write("rh-cav-time", timeUtilizationRH_CAVsum(idx))
        write("rh-cav-distance", distanceUtilizationRH_CAVsum(idx))
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {

  }

  override def resetStats(): Unit = {
    rideHailCav.clear()
    ridehailHumanDriven.clear()
    personalCav.clear()
    personalHumanDriven.clear()

    timeUtilizationRH_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    distanceUtilizationRH_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    timeUtilizationRH_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    distanceUtilizationRH_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    timeUtilizationPV_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    distanceUtilizationPV_CAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    timeUtilizationPV_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    distanceUtilizationPV_noCAV = Array.ofDim[Double](timeBins.size, keys.values.max + 1)

  }

  def assignVehicleDayToLocationMatrix(
    days: ListBuffer[Event],
    isRH: Boolean,
    isCAV: Boolean
  ): (Array[Array[Double]], Array[Array[Double]]) = {
    val timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    val distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    if (isRH) {
      if (isCAV)
        timeBins.indices.foreach(timeUtilization(_)(keys("idle")) += 1)
      else
        timeBins.indices.foreach(timeUtilization(_)(keys("offline")) += 1)
    } else {
      timeBins.indices.foreach(timeUtilization(_)(keys("parked")) += 1)
    }

    var chargingNext = false
    var pickupNext = false
    days.zipWithIndex.foreach(eventIndex => {
      val event = eventIndex._1
      val idx = eventIndex._2
      val lastEvent = idx == days.size - 1
      if (lastEvent) {
        chargingNext = false
        pickupNext = false
      } else {
        val chargingDirectlyNext = days(idx + 1).getEventType == "RefuelSessionEvent"
        val chargingOneAfter =
          if (idx == days.size - 2)
            false
          else
            days(idx + 1).getEventType == "ParkEvent" && days(idx + 2).getEventType == "RefuelSessionEvent"
        chargingNext = chargingDirectlyNext || chargingOneAfter
        pickupNext = days(idx + 1).getEventType == "PathTraversal" && days(idx + 1).getAttributes
          .get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)
          .toInt >= 1
      }
      val eventCharacteristics = classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH, isCAV)
      val afterEventStart = timeBins.map(_ >= eventCharacteristics.start)
      val duringEvent = afterEventStart
        .zip(timeBins.map(_ < eventCharacteristics.end))
        .map(afterEventBin => afterEventBin._1 && afterEventBin._2)

      afterEventStart.zipWithIndex.foreach(indexValue => {
        if (indexValue._1)
          timeUtilization(indexValue._2).indices.foreach(timeUtilization(indexValue._2)(_) = 0.0)
      })

      duringEvent.zipWithIndex.foreach(indexValue => {
        if (indexValue._1) {
          timeUtilization(indexValue._2)(keys(eventCharacteristics.eventType)) += 1.0
        }
      })


      event match {
        case event: PathTraversalEvent =>
          val sum = duringEvent.count(during => during)
          if (sum > 0) {
            val meanDistancePerTime = event.legLength / sum
            duringEvent.zipWithIndex.foreach(indexValue => {
              if (indexValue._1) {
                distanceUtilization(indexValue._2)(keys(eventCharacteristics.eventType)) += meanDistancePerTime / 1609.34
              }
            })
          } else {
            val firstIndex = afterEventStart.indexOf(true)
            if (firstIndex > 0)
              distanceUtilization(firstIndex)(keys(eventCharacteristics.eventType)) += event.legLength / 1609.34
          }
        case _ =>
      }

      eventCharacteristics.nextType.foreach(nextType => {
        val afterEventEnd = timeBins.map(_ >= eventCharacteristics.end)
        afterEventEnd.zipWithIndex.foreach(indexValue => {
          if (indexValue._1) {
            timeUtilization(indexValue._2)(keys(nextType)) += 1.0
          }
        })
      })
    })

    (timeUtilization, distanceUtilization)
  }

  def classifyEventLocation(
    event: Event,
    lastEvent: Boolean,
    chargingNext: Boolean,
    pickupNext: Boolean,
    isRH: Boolean,
    isCAV: Boolean
  ): EventStatus = {
    event match {
      case event: PathTraversalEvent =>
        if (isRH) {
          if (event.numberOfPassengers >= 1) {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("queuing"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-full", Some("idle"))
            }
          } else {
            if (lastEvent) {
              if (isCAV)
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("offline"))
            } else {
              if (chargingNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-tocharger", Some("queuing"))
              else if (pickupNext)
                EventStatus(event.departureTime, event.arrivalTime, "driving-topickup", Some("idle"))
              else
                EventStatus(event.departureTime, event.arrivalTime, "driving-reposition", Some("idle"))
            }
          }
        } else {
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
        if (isRH) {
          if (lastEvent)
            EventStatus(event.getTime, event.getTime + duration, "charging", Some("offline"))
          else
            EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
        } else {
          EventStatus(event.getTime, event.getTime + duration, "charging", Some("parked"))
        }
      case event: ParkEvent =>
        if (isRH)
          EventStatus(event.getTime, 30 * 3600, "idle", None)
        else
          EventStatus(event.getTime, 30 * 3600, "parked", None)
      case _ =>
        EventStatus(0.0, 0.0, "Unknown", None)
    }
  }
}
