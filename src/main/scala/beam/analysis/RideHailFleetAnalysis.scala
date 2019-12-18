package beam.analysis

import beam.agentsim.events.{ParkEvent, PathTraversalEvent, RefuelSessionEvent, RideHailFleetStateEvent}
import beam.analysis.plots.GraphAnalysis
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.metrics.{Metrics, SimulationMetricCollector}
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class EventStatus(start: Double, end: Double, eventType: String, nextType: Option[String] = None)

class RideHailFleetAnalysis(beamServices: BeamServices) extends GraphAnalysis {

  val resolutionInSeconds = 60
  val timeBins = 0 until 24 * 3600 by resolutionInSeconds
  var processedHour = 0

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

  import RefuelSessionEvent._

  val rideHailCav = mutable.Map[String, ArrayBuffer[Event]]()
  val ridehailHumanDriven = mutable.Map[String, ArrayBuffer[Event]]()
  val personalCav = mutable.Map[String, ArrayBuffer[Event]]()
  val personalHumanDriven = mutable.Map[String, ArrayBuffer[Event]]()
  val cavSet = mutable.Set[String]()
  val ridehailVehicleSet = mutable.Set[String]()

  override def processStats(event: Event): Unit = {
    event match {
      case refuelSessionEvent: RefuelSessionEvent =>
        if (refuelSessionEvent.getAttributes.get(ATTRIBUTE_ENERGY_DELIVERED).toDouble > 0.0) {
          val vehicle = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_ID)
          val vehicleType = refuelSessionEvent.getAttributes.get(RefuelSessionEvent.ATTRIBUTE_VEHICLE_TYPE)
          val rideHail = vehicle.contains("rideHail")
          val cav = vehicleType.contains("L5")
          if (rideHail && cav) {
            collectEvent(
              rideHailCav,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle
            )
          } else if (rideHail && !cav) {
            collectEvent(
              ridehailHumanDriven,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle
            )
          } else if (!rideHail && cav) {
            collectEvent(
              personalCav,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle
            )
          } else if (!rideHail && !cav) {
            collectEvent(
              personalHumanDriven,
              refuelSessionEvent.copy(tick = refuelSessionEvent.getTime - refuelSessionEvent.sessionDuration + 0.5),
              vehicle
            )
          }
        }

      case pathTraversalEvent: PathTraversalEvent =>
        if (pathTraversalEvent.mode == BeamMode.CAR) {
          val vehicle = pathTraversalEvent.vehicleId.toString
          val rideHail = vehicle.contains("rideHail")
          val cav = pathTraversalEvent.vehicleType.contains("L5")

          if (rideHail && cav) {
            collectEvent(
              rideHailCav,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle
            )
          } else if (rideHail && !cav) {
            collectEvent(
              ridehailHumanDriven,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle
            )
          } else if (!rideHail && cav) {
            collectEvent(
              personalCav,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle
            )
          } else if (!rideHail && !cav) {
            collectEvent(
              personalHumanDriven,
              pathTraversalEvent.copy(time = pathTraversalEvent.departureTime.toDouble - 0.5),
              vehicle
            )
          }
        }

      case parkEvent: ParkEvent =>
        val vehicle = parkEvent.vehicleId.toString
        if (rideHailCav.contains(vehicle)) {
          collectEvent(rideHailCav, parkEvent, vehicle)
        } else if (ridehailHumanDriven.contains(vehicle)) {
          collectEvent(ridehailHumanDriven, parkEvent, vehicle)
        } else if (personalCav.contains(vehicle)) {
          collectEvent(personalCav, parkEvent, vehicle)
        } else if (personalHumanDriven.contains(vehicle)) {
          collectEvent(personalHumanDriven, parkEvent, vehicle)
        }
      case _ =>
    }
  }

  override def createGraph(event: IterationEndsEvent): Unit = {
    processVehicleStates()
  }

  def collectEvent(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    event: Event,
    vehicle: String
  ): Unit = {
    val events: ArrayBuffer[Event] = vehicleEventTypeMap.getOrElse(vehicle, new ArrayBuffer[Event]())
    events += event
    vehicleEventTypeMap(vehicle) = events
    val hour = eventHour(event.getTime)
    if (hour > processedHour) {
      processVehicleStates()
      processedHour = hour
    }
  }

  def processVehicleStates() {
    processEvents(rideHailCav, true, true, "rh-cav")
    processEvents(ridehailHumanDriven, true, false, "rh-no-cav")
    processEvents(personalCav, false, true, "bev-cav")
    processEvents(personalHumanDriven, false, false, "bev-no-cav")
  }

  def eventHour(time: Double): Int = (time / 3600).toInt

  def processEvents(
    vehicleEventTypeMap: mutable.Map[String, ArrayBuffer[Event]],
    isRH: Boolean,
    isCAV: Boolean,
    graphName: String
  ) {
    var timeUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    var distanceUtilization = Array.ofDim[Double](timeBins.size, keys.values.max + 1)
    vehicleEventTypeMap.values.foreach(now => {
      val timesDistances = assignVehicleDayToLocationMatrix(now, isRH, isCAV)
      timeUtilization = timesDistances._1.zip(timeUtilization).map(time => (time._1, time._2).zipped.map(_ + _))
      distanceUtilization = timesDistances._2
        .zip(distanceUtilization)
        .map(distance => (distance._1, distance._2).zipped.map(_ + _))
    })

    if (processedHour < timeBins.length / 60) {
      (0 until processedHour).foreach(hour => {

        val timeUtilizationSum =
          timeUtilization
            .slice(hour * 60, (hour + 1) * 60)
            .reduce((xSeries1, xSeries2) => (xSeries1, xSeries2).zipped.map(_ + _))
        val distanceUtilizationSum =
          distanceUtilization
            .slice(hour * 60, (hour + 1) * 60)
            .reduce((xSeries1, xSeries2) => (xSeries1, xSeries2).zipped.map(_ + _))

        keys.foreach {
          case (key, idx) =>
            def write(metric: String, value: Double): Unit = {
              val tags = Map("vehicle-state" -> key)
              beamServices.simMetricCollector.write(
                metric,
                SimulationTime((hour + 1) * 60 * 60),
                Map(SimulationMetricCollector.defaultMetricValueName -> value),
                tags
              )
            }
            write(s"$graphName-time", timeUtilizationSum(idx))
            write(s"$graphName-distance", distanceUtilizationSum(idx))
        }
      })
    }
  }

  override def resetStats(): Unit = {
    rideHailCav.clear()
    ridehailHumanDriven.clear()
    personalCav.clear()
    personalHumanDriven.clear()
    processedHour = 0
  }

  def assignVehicleDayToLocationMatrix(
    days: ArrayBuffer[Event],
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
          if (lastEvent) {
            if (isCAV)
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
            else
              EventStatus(event.getTime, event.getTime + duration, "charging", Some("offline"))
          } else
            EventStatus(event.getTime, event.getTime + duration, "charging", Some("idle"))
        } else {
          EventStatus(event.getTime, event.getTime + duration, "charging", Some("parked"))
        }
      case event: ParkEvent =>
        if (isRH)
          EventStatus(event.getTime, 30 * 3600, "idle")
        else
          EventStatus(event.getTime, 30 * 3600, "parked")
      case _ =>
        EventStatus(0.0, 0.0, "Unknown")
    }
  }
}
