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

class TNCIterationsStatsCollector(eventsManager: EventsManager, beamConfig: BeamConfig) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption


  // timeBins -> number OfTimeBins input
  val rideHaillingConfig = beamConfig.beam.agentsim.agents.rideHailing
  val timeBinSizeInSec = 3600 // beam.agentsim.agents.rideHailing.
  val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSizeInSec).toInt + 1

  val rideHailModeChoice4Waiting = mutable.Map[String, ModeChoiceEvent]()
  val waitingTimeEvents = mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()
  val pathTraversedVehicles = mutable.Map[String, PathTraversalEvent]()

  val rideHailModeChoiceAndPersonEntersEvents = mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()

  val vehicleIdlingStartTimeBins = mutable.Map[String, Int]()


  val rideHailStats = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

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

    rideHailStats.foreach {
      (rhs) => {
        System.out.println(rhs._1)

        rhs._2.foreach(
          rhse => {
            System.out.println(rhse)
          }
        )
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

        calculateStats(event.asInstanceOf[PathTraversalEvent])
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

    if (mode.equals("ride_hailing")) {

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

  def calculateStats(event: PathTraversalEvent): Unit = {

    processPathTraversalEvent(event)

    val mode = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
    val vehicleId = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)

    if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")) {

      calculateIdlingVehiclesStats(vehicleId, event)
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

          val binIndex = getTimeBin(startTime)

          val tazId = getTazId(event)

          rideHailStats.get(tazId) match {
            case Some(entries: ArrayBuffer[Option[RideHailStatsEntry]]) => {
              val entry = entries(binIndex)

              entry match {
                case Some(e) =>
                  val _entry = e.copy(sumOfRequestedRides = e.sumOfRequestedRides + 1,
                    sumOfWaitingtimes = e.sumOfWaitingtimes + waitingTime)
                  entries(binIndex) = Some(_entry)
                case None =>
                  entries(binIndex) = Some(RideHailStatsEntry(1, waitingTime, 0))
              }

              updateIdlingStats(event, vehicleId, buffer)
            }
            case None => {
              val buffer: mutable.ArrayBuffer[Option[RideHailStatsEntry]] = mutable.ArrayBuffer.fill(numberOfTimeBins)(None)

              val entry = RideHailStatsEntry(1, waitingTime, 0)

              buffer(binIndex) = Some(entry)

              updateIdlingStats(event, vehicleId, buffer)

              rideHailStats.put(tazId, buffer)
            }
          }

          rideHailModeChoiceAndPersonEntersEvents.remove(vehicleId)
        }
        case None =>
      }
    }
  }

  def updateIdlingStats(event: PathTraversalEvent, vehicleId: String, buffer: ArrayBuffer[RideHailStatsEntry]) = {
    // Idling Times
    vehicleIdlingStartTimeBins.get(vehicleId) match {
      case Some(bi) => {
        val departureTime = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toDouble
        val arrivalTime = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toDouble

        val idleEndTimeBinIndex = getTimeBin(departureTime)
        val idleStartTimeBinIndex = getTimeBin(arrivalTime)
        vehicleIdlingStartTimeBins.put(vehicleId, idleStartTimeBinIndex)

        val startIdx = bi + 1
        for(i <- startIdx until idleEndTimeBinIndex){

          val _e = buffer(i)
          buffer(i) = _e.copy(sumOfIdlingVehicles = _e.sumOfIdlingVehicles + 1)
        }
      }
      case None => {
        val departureTime = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toDouble
        val arrivalTime = event.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toDouble

        val idleEndTimeBinIndex = getTimeBin(departureTime)
        val idleStartTimeBinIndex = getTimeBin(arrivalTime)
        vehicleIdlingStartTimeBins.put(vehicleId, idleStartTimeBinIndex)

        val startIdx = 0
        for(i <- startIdx until idleEndTimeBinIndex){

          val _e = buffer(i)
          buffer(i) = _e.copy(sumOfIdlingVehicles = _e.sumOfIdlingVehicles + 1)
        }
      }
    }
  }


  def calculateIdlingVehiclesStats(vehicleId: String, currentEvent: PathTraversalEvent) = {
    pathTraversedVehicles.get(vehicleId) match {
      case Some(lastEvent) => {
        pathTraversedVehicles.put(vehicleId, currentEvent)

        val lastCoord = (lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X).toDouble,
                          lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y).toDouble)
        val currentCoord = (currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble,
                            currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble)

        if(lastCoord == currentCoord) {

          val lastArrival = lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong
          val currentDeparture = currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong

          val lastActive = getTimeBin(lastArrival)
          val currentActive = getTimeBin(currentDeparture)

          val vehicleIdleTime = currentDeparture - lastArrival

        }
      }
      case None => {
        pathTraversedVehicles.put(vehicleId, currentEvent)
      }
    }
  }

  private def getTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val startX = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble
    val startY = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble

    val tazId = getTazId(startX, startY)
    tazId
  }

  private def getTazId(startX: Double, startY: Double): String = {
    mTazTreeMap.get.getTAZ(startX, startY).tazId.toString
  }

  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }
}