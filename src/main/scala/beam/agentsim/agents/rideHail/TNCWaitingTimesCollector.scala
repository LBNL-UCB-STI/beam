package beam.agentsim.agents.rideHail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try


case class RideHailStatsEntry(sumOfRequestedRides: Double, sumOfWaitingtimes: Double, sumOfIdlingVehicles: Double)

/*
class RideHailStats(mutable.Map[String, ArrayBuffer[RideHailStatsEntry]]){
  def getRideHailingStats(coord: Coord, time: Double): RideHailStatsEntry = {
  }
}*/

class TNCWaitingTimesCollector(eventsManager: EventsManager, beamConfig: BeamConfig) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption


  // timeBins -> number OfTimeBins input
  val rideHaillingConfig = beamConfig.beam.agentsim.agents.rideHailing
  val timeBinSize = rideHaillingConfig.surgePricing.timeBinSize
  val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSize).toInt + 1

  val rideHailModeChoice4Waiting = mutable.Map[String, ModeChoiceEvent]()

  val rideHailModeChoiceAndPersonEntersEvents = mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()

  val rideHailStats = mutable.Map[String, ArrayBuffer[RideHailStatsEntry]]()



  //numberOfRides: -> passengers =1 (sum of rides)
  //customerWaitTime -> sum and average

  //idleTimes = count in each bin according to how much time remaining
  // agent arrives in a time 1000 and leaves at time 2000
  // bin Size=100 -> count as idle in 10 bins (from 1000 to 2000)
  //idleTime[TAZId,binNumber] // bin 10, 11, 12,...19 we do +1


  eventsManager.addHandler(this)

  def getTNCIdlingTimes(): Set[WaitingEvent] = {
    ???
  }

  def getTNCPassengerWaitingTimes(): Set[WaitingEvent] = {
    ???
  }

  def tellHistoryToRideHailIterationHistoryActor(): Unit = {
    // TODO: send message to actor with collected data

    System.out.println("Inside tellHistoryToRideHailIterationHistoryActor")

    rideHailStats.foreach{
      (rhs) => {
        System.out.println(rhs._1 + " - " + rhs._2)
      }
    }
  }

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {
      case ModeChoiceEvent.EVENT_TYPE => {

        collectModeChoiceEvents(event.asInstanceOf[ModeChoiceEvent])
      }
      case PersonEntersVehicleEvent.EVENT_TYPE => {

        collectPersonEntersEvents(event.asInstanceOf[PersonEntersVehicleEvent])
      }
      case PathTraversalEvent.EVENT_TYPE => {

        processPathTraversalEvent(event.asInstanceOf[PathTraversalEvent])
      }
      case _ =>
    }
  }

  /*
    Calculating sumOfRides

    collectRides
    1. This method will collect all the ModeChoice events where the mode is 'rideHailing'
    2. Afterwards when a PathTraversal event occurs for the same vehicle with num_passengers = 1 we will find the tazid
      using coords from the PathTraversal event
   */
  def collectModeChoiceEvents(event: ModeChoiceEvent): Unit = {

    val mode = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)

    if(mode.equals("ride_hailing")) {

      val personId = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      rideHailModeChoice4Waiting.put(personId, event)

    }
  }

  def collectPersonEntersEvents(personEntersVehicleEvent: PersonEntersVehicleEvent): Unit = {

    val personId = personEntersVehicleEvent.getPersonId.toString
    val vehicleId = personEntersVehicleEvent.getVehicleId.toString

    if(vehicleId.contains("rideHail")) {
      rideHailModeChoice4Waiting.get(personId) match {
        case Some(e) => {

          rideHailModeChoiceAndPersonEntersEvents.put(vehicleId, (e, personEntersVehicleEvent))

          rideHailModeChoice4Waiting.remove(personId)
        }
        case None =>
      }
    }
  }



  def processPathTraversalEvent(event: PathTraversalEvent): Unit = {

    val vehicleId = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
    val numPassengers = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toDouble

    if(numPassengers > 0) {

      rideHailModeChoiceAndPersonEntersEvents.get(vehicleId) match {
        case Some(el) => {

          val modeChoiceEvent = el._1
          val personEntersVehicleEvent = el._2

          val startTime = modeChoiceEvent.getTime
          val endTime = personEntersVehicleEvent.getTime
          val waitingTime = endTime - startTime

          val binIndex = getEventHour(startTime)

          val startX = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble
          val startY = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble
          val tazId = getTazId(startX, startY)

          rideHailStats.get(tazId) match {
            case Some(entries: ArrayBuffer[RideHailStatsEntry]) => {
              val entry = entries(binIndex)

              if (entry == null) {
                entries(binIndex) = RideHailStatsEntry(1, waitingTime, 0)
              } else {
                val _entry = entry.copy(sumOfRequestedRides = entry.sumOfRequestedRides + 1,
                  sumOfWaitingtimes = entry.sumOfWaitingtimes + waitingTime)
                entries(binIndex) = _entry
              }
            }
            case None => {
              val entry = RideHailStatsEntry(1, waitingTime, 0)
              val buffer = ArrayBuffer[RideHailStatsEntry]()
              buffer += entry
              rideHailStats.put(tazId, buffer)
            }
          }

          rideHailModeChoiceAndPersonEntersEvents.remove(vehicleId)
        }
        case None =>
      }
    }
  }


  private def getTazId(startX: Double, startY: Double): String = {
    mTazTreeMap.get.getTAZ(startX, startY).tazId.toString
  }

  private def getEventHour(time: Double): Int = {
    (time / timeBinSize).toInt
  }
}