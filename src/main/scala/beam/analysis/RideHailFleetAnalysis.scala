package beam.analysis

import beam.agentsim.events.{ParkEvent, PathTraversalEvent, RefuelSessionEvent, RideHailFleetStateEvent}
import beam.analysis.plots.GraphAnalysis
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.metrics.Metrics
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
            processEvents(
              rideHailCav,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle,
              true,
              true,
              "rh-cav"
            )
          } else if (rideHail && !cav) {
            processEvents(
              ridehailHumanDriven,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle,
              true,
              false,
              "rh-no-cav"
            )
          } else if (!rideHail && cav) {
            processEvents(
              personalCav,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle,
              false,
              true,
              "bev-cav"
            )
          } else if (!rideHail && !cav) {
            processEvents(
              personalHumanDriven,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle,
              false,
              false,
              "bev-no-cav"
            )
          }
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if (pathTraversalEvent.mode == BeamMode.CAR) {
          val vehicle = pathTraversalEvent.vehicleId.toString
          val rideHail = vehicle.contains("rideHail")
          val cav = pathTraversalEvent.vehicleType.contains("L5")

          if (rideHail && cav) {
            processEvents(
              rideHailCav,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle,
              true,
              true,
              "rh-cav"
            )
          } else if (rideHail && !cav) {
            processEvents(
              ridehailHumanDriven,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle,
              true,
              false,
              "rh-no-cav"
            )
          } else if (!rideHail && cav) {
            processEvents(
              personalCav,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle,
              false,
              true,
              "bev-cav"
            )
          } else if (!rideHail && !cav) {
            processEvents(
              personalHumanDriven,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle,
              false,
              false,
              "bev-no-cav"
            )
          }
        }

      case parkEvent: ParkEvent =>
        val vehicle = parkEvent.vehicleId.toString
        if (rideHailCav.contains(vehicle)) {
          processEvents(rideHailCav, parkEvent, vehicle, true, true, "rh-cav")
        } else if (ridehailHumanDriven.contains(vehicle)) {
          processEvents(ridehailHumanDriven, parkEvent, vehicle, true, false, "rh-no-cav")
        } else if (personalCav.contains(vehicle)) {
          processEvents(personalCav, parkEvent, vehicle, false, true, "bev-cav")
        } else if (personalHumanDriven.contains(vehicle)) {
          processEvents(personalHumanDriven, parkEvent, vehicle, false, false, "bev-no-cav")
        }
      case _ =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {}

  def processEvents(
    vehicleEventTypeMap: mutable.Map[String, ListBuffer[Event]],
    event: Event,
    vehicle: String,
    isRH: Boolean,
    isCAV: Boolean,
    graphName: String
  ) {
    return

    val events: ListBuffer[Event] = vehicleEventTypeMap.getOrElse(vehicle, new ListBuffer[Event]())
    events += event
    vehicleEventTypeMap(vehicle) = events
    var timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    var distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    vehicleEventTypeMap.values.foreach(now => {
      val timesDistances = assignVehicleDayToLocationMatrix(now, isRH, isCAV)
      timeUtilization = timesDistances._1.zip(timeUtilization).map(time => (time._1, time._2).zipped.map(_ + _))
      distanceUtilization = timesDistances._2
        .zip(distanceUtilization)
        .map(distance => (distance._1, distance._2).zipped.map(_ + _))
    })

    val timeUtilizationSum =
      timeUtilization.reduce((xSeries1, xSeries2) => (xSeries1, xSeries2).zipped.map(_ + _))
    val distanceUtilizationSum =
      distanceUtilization.reduce((xSeries1, xSeries2) => (xSeries1, xSeries2).zipped.map(_ + _))

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
        write(s"$graphName-time", timeUtilizationSum(idx))
        write(s"$graphName-distance", distanceUtilizationSum(idx))
    }
  }

  override def resetStats(): Unit = {
    rideHailCav.clear()
    ridehailHumanDriven.clear()
    personalCav.clear()
    personalHumanDriven.clear()
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

    days.zipWithIndex.foreach(eventIndex => {
      val event = eventIndex._1
      val idx = eventIndex._2
      val lastEvent = idx == days.size - 1
      var chargingNext = false
      var pickupNext = false

      if (!lastEvent) {
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
      val eventIdx = keys(eventCharacteristics.eventType)

      val afterDurationEventStart = timeBins
        .map(timeBin => {
          val eventStart = timeBin >= eventCharacteristics.start
          val duringEvent = eventStart && timeBin < eventCharacteristics.end
          (eventStart, duringEvent)
        })
        .unzip

      val afterEventStart = afterDurationEventStart._1
      val duringEvent = afterDurationEventStart._2

      afterEventStart.zipWithIndex.foreach(indexValue => {
        if (indexValue._1)
          timeUtilization(indexValue._2).indices.foreach(timeUtilization(indexValue._2)(_) = 0.0)
      })

      duringEvent.zipWithIndex.foreach(indexValue => {
        if (indexValue._1) {
          timeUtilization(indexValue._2)(eventIdx) += 1.0
        }
      })

      if (event.getEventType == "PathTraversal") {
        val sum = duringEvent.count(during => during)
        val legLength = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_LENGTH).toDouble
        if (sum > 0) {
          val meanDistancePerTime = legLength / sum
          duringEvent.zipWithIndex.foreach(indexValue => {
            if (indexValue._1) {
              distanceUtilization(indexValue._2)(eventIdx) += meanDistancePerTime / 1609.34
            }
          })
        } else {
          val firstIndex = afterEventStart.indexOf(true)
          if (firstIndex > 0)
            distanceUtilization(firstIndex)(eventIdx) += legLength / 1609.34
        }
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
