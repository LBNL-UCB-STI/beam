package beam.agentsim.agents.rideHail

import akka.actor.ActorRef
import beam.agentsim.agents.rideHail.RideHailIterationHistoryActor.UpdateRideHailStats
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


case class RideHailStatsEntry(sumOfRequestedRides: Long, sumOfWaitingtimes: Long, sumOfIdlingVehicles: Long) {

  def getAverage(other: RideHailStatsEntry): RideHailStatsEntry = {
    RideHailStatsEntry((sumOfRequestedRides + other.sumOfRequestedRides) / 2, (sumOfWaitingtimes + other.sumOfWaitingtimes) / 2, (sumOfIdlingVehicles + other.sumOfIdlingVehicles) / 2)
  }
}


/*
class RideHailStats(mutable.Map[String, ArrayBuffer[RideHailStatsEntry]]){
  def getRideHailingStats(coord: Coord, time: Double): RideHailStatsEntry = {
  }
}*/

class TNCIterationsStatsCollector(eventsManager: EventsManager, beamConfig: BeamConfig, rideHailIterationHistoryActor: ActorRef) extends BasicEventHandler {

  // TAZ level -> how to get as input here?
  private val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption


  // timeBins -> number OfTimeBins input
  private val rideHailingConfig = beamConfig.beam.agentsim.agents.rideHailing
  private val timeBinSizeInSec = beamConfig.beam.agentsim.agents.rideHailing.iterationStats.timeBinSizeInSec
  private val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSizeInSec).toInt + 1

  private val rideHailModeChoiceEvents = mutable.Map[String, ModeChoiceEvent]()
  private val rideHailEventsTuples = mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()
  private val vehicleActiveBins = mutable.Map[String, mutable.Map[String, mutable.Set[Int]]]()

  private var rideHailStats = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

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

  def tellHistoryToRideHailIterationHistoryActorAndReset(): Unit = {
    // TODO: send message to actor with collected data

    //println("Inside tellHistoryToRideHailIterationHistoryActor")
    updateStatsForIdlingVehicles()

    rideHailIterationHistoryActor ! UpdateRideHailStats(TNCIterationStats(rideHailStats, mTazTreeMap.get, timeBinSizeInSec, numberOfTimeBins))

    rideHailStats.foreach {
      (rhs) => {
       // println(rhs._1)

        rhs._2.foreach(
          rhse => {
           // println(rhse)
          }
        )
      }
    }

    rideHailStats = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    rideHailModeChoiceEvents.clear()

    vehicleActiveBins.clear()

    rideHailEventsTuples.clear()
  }

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {
      case ModeChoiceEvent.EVENT_TYPE => {

        collectModeChoiceEvents(ModeChoiceEvent.apply(event))
      }
      case PersonEntersVehicleEvent.EVENT_TYPE => {

        collectPersonEntersEvents(event.asInstanceOf[PersonEntersVehicleEvent])
      }
      case PathTraversalEvent.EVENT_TYPE => {

        calculateStats(PathTraversalEvent.apply(event))
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
  private def collectModeChoiceEvents(event: ModeChoiceEvent): Unit = {

    val mode = event.getAttributes.get(ModeChoiceEvent.ATTRIBUTE_MODE)

    if (mode.equals("ride_hailing")) {

      val personId = event.getAttributes().get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      rideHailModeChoiceEvents.put(personId, event)
    }
  }

  private def collectPersonEntersEvents(personEntersVehicleEvent: PersonEntersVehicleEvent): Unit = {

    val personId = personEntersVehicleEvent.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON)
    val vehicleId = personEntersVehicleEvent.getAttributes().get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)

    if (vehicleId.contains("rideHail")) {
      rideHailModeChoiceEvents.get(personId) match {
        case Some(modeChoiceEvent) => {

          rideHailEventsTuples.put(vehicleId, (modeChoiceEvent, personEntersVehicleEvent))

          rideHailModeChoiceEvents.remove(personId)
        }
        case None =>
      }
    }
  }

  private def calculateStats(pathTraversalEvent: PathTraversalEvent): Unit = {

    processPathTraversalEvent(pathTraversalEvent)

    val mode = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_MODE)
    val vehicleId = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)

    if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")) {

      collectActiveVehicles(vehicleId, pathTraversalEvent)
    }
  }

  private def processPathTraversalEvent(pathTraversalEvent: PathTraversalEvent): Unit = {

    val vehicleId = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
    val numPassengers = pathTraversalEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt


    rideHailEventsTuples.get(vehicleId) match {
      case Some(events) => {

        val modeChoiceEvent = events._1
        val personEntersVehicleEvent = events._2

        val startTime = modeChoiceEvent.getTime.toLong
        val endTime = personEntersVehicleEvent.getTime.toLong
        val waitingTime = endTime - startTime

        val binIndex = getTimeBin(startTime)
        val tazId = getTazId(pathTraversalEvent)

        val tazBins = rideHailStats.get(tazId) match {
          case Some(bins) => bins
          case None => mutable.ArrayBuffer.fill[Option[RideHailStatsEntry]](numberOfTimeBins)(None)
        }

        tazBins(binIndex) = tazBins(binIndex) match {
          case Some(entry) =>
            val sumOfWaitingTimes = if(numPassengers > 0) entry.sumOfWaitingtimes + waitingTime else entry.sumOfWaitingtimes
            val numOfRequestedRides = entry.sumOfRequestedRides + 1
           Some(entry.copy(sumOfRequestedRides = numOfRequestedRides,
              sumOfWaitingtimes = sumOfWaitingTimes))
          case None =>
            Some(RideHailStatsEntry(1, waitingTime, 0))
        }

        rideHailStats.put(tazId, tazBins)

        rideHailEventsTuples.remove(vehicleId)
      }
      case None =>
    }
  }


  private def collectActiveVehicles(vehicleId: String, currentEvent: PathTraversalEvent) = {

    val tazId = getTazId(currentEvent)

    val tazVehs = vehicleActiveBins.get(tazId) match {
      case Some(vehicles) => vehicles
      case None => mutable.Map.empty[String, mutable.Set[Int]]
    }

    var activeBins = tazVehs.get(vehicleId) match {
      case Some(bins) => bins
      case None => mutable.Set[Int]()
    }

    val startTIme = currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME).toLong
    val endTime = currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME).toLong

    val startBin = getTimeBin(startTIme)
    val endingBin = getTimeBin(endTime)

    activeBins ++= (startBin to endingBin).toSet

    tazVehs.put(vehicleId, activeBins)
    vehicleActiveBins.put(tazId, tazVehs)
  }

  private def updateStatsForIdlingVehicles(): Unit = {

    rideHailStats.foreach(items => {

      val tazId = items._1
      val bins = items._2

      bins.zipWithIndex.foreach(bin => {

        val binIndex = bin._2
        val noOfIdlingVehicles = getNoOfIdlingVehicle(tazId, binIndex)

        bins(binIndex) = bin._1 match {
          case Some(entry) => Some(entry.copy(sumOfIdlingVehicles = noOfIdlingVehicles))
          case None => Some(RideHailStatsEntry(0, 0, noOfIdlingVehicles))
        }
      })
    })
  }

  private def getNoOfIdlingVehicle(tazId: String, binIndex: Int): Int = {
    val totalVehicles = vehicleActiveBins.flatMap(_._2.keySet).size
    vehicleActiveBins.get(tazId) match {
      case Some(vehBins) =>
        val noOfActiveVehicles = vehBins.count(_._2.contains(binIndex))
        totalVehicles - noOfActiveVehicles
      case None =>
        totalVehicles
    }
  }

  private def isSameCoords(currentEvent: PathTraversalEvent, lastEvent: PathTraversalEvent) = {
    val lastCoord = (lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X).toDouble,
      lastEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y).toDouble)
    val currentCoord = (currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X).toDouble,
      currentEvent.getAttributes.get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y).toDouble)
    lastCoord == currentCoord
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

