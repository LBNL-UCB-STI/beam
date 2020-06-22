package beam.agentsim.agents.ridehail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/**
  * numberOfRides: -> passengers =1 (sum of rides)
  * customerWaitTime -> sum and average
  *
  * idleTimes = count in each bin according to how much time remaining
  * agent arrives in a time 1000 and leaves at time 2000
  * bin Size=100 -> count as idle in 10 bins (from 1000 to 2000)
  * idleTime[TAZId,binNumber] // bin 10, 11, 12,...19 we do +1
  *
  * @param sumOfRequestedRides Total number of ride requests
  * @param sumOfWaitingTimes   Sum of waiting times
  * @param sumOfIdlingVehicles total number of idling vehicles
  */
case class RideHailStatsEntry(
  sumOfRequestedRides: Long,
  sumOfWaitingTimes: Long,
  sumOfIdlingVehicles: Long,
  sumOfActivityEndEvents: Long
) {

  def aggregate(other: RideHailStatsEntry): RideHailStatsEntry =
    RideHailStatsEntry(
      sumOfRequestedRides + other.sumOfRequestedRides,
      sumOfWaitingTimes + other.sumOfWaitingTimes,
      sumOfIdlingVehicles + other.sumOfIdlingVehicles,
      sumOfActivityEndEvents + other.sumOfActivityEndEvents
    )

  def average(other: RideHailStatsEntry): RideHailStatsEntry = {
    RideHailStatsEntry(
      (sumOfRequestedRides + other.sumOfRequestedRides) / 2,
      (sumOfWaitingTimes + other.sumOfWaitingTimes) / 2,
      (sumOfIdlingVehicles + other.sumOfIdlingVehicles) / 2,
      (sumOfActivityEndEvents + other.sumOfActivityEndEvents) / 2
    )
  }

  def getDemandEstimate: Double = {
    sumOfRequestedRides + sumOfActivityEndEvents
  }
}

object RideHailStatsEntry {

  def aggregate(rideHailStats: List[Option[RideHailStatsEntry]]): RideHailStatsEntry =
    rideHailStats.flatten.reduceOption(aggregate).getOrElse(empty)

  def empty: RideHailStatsEntry = RideHailStatsEntry()

  def apply(
    sumOfRequestedRides: Long = 0,
    sumOfWaitingTimes: Long = 0,
    sumOfIdlingVehicles: Long = 0,
    sumOfActivityEndEvents: Long = 0
  ): RideHailStatsEntry =
    new RideHailStatsEntry(
      sumOfRequestedRides,
      sumOfWaitingTimes,
      sumOfIdlingVehicles,
      sumOfActivityEndEvents
    )

  def aggregate(first: RideHailStatsEntry, second: RideHailStatsEntry): RideHailStatsEntry =
    first.aggregate(second)
}

class RideHailIterationsStatsCollector(
  eventsManager: EventsManager,
  beamServices: BeamServices,
  rideHailIterationHistoryActor: RideHailIterationHistory,
  transportNetwork: TransportNetwork
) extends BasicEventHandler
    with LazyLogging {

  private val beamConfig = beamServices.beamConfig
  // TAZ level -> how to get as input here?

  // timeBins -> number OfTimeBins input
  private val rideHailConfig = beamConfig.beam.agentsim.agents.rideHail
  private val timeBinSizeInSec = rideHailConfig.iterationStats.timeBinSizeInSec
  private val numberOfTimeBins = Math
    .floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSizeInSec)
    .toInt + 1

  private val rideHailModeChoiceEvents = mutable.Map[String, ModeChoiceEvent]()
  private val rideHailEventsTuples =
    mutable.Map[String, (ModeChoiceEvent, PersonEntersVehicleEvent)]()
  private val rideHailLastEvent = mutable.Map[String, PathTraversalEvent]()
  private val vehicleIdlingBins =
    mutable.Map[String, mutable.Map[Int, String]]()
  private val vehicles = mutable.Map[String, Int]()

  var rideHailStats: Map[String, ArrayBuffer[Option[RideHailStatsEntry]]] =
    Map()

  eventsManager.addHandler(this)

  private var neverMovedVehicles: IndexedSeq[String] = Vector.empty

  def getNeverMovedVehicles: IndexedSeq[String] = {
    neverMovedVehicles
  }

  def tellHistoryToRideHailIterationHistoryActorAndReset(): Unit = {
    updateStatsForIdlingVehicles()

    rideHailIterationHistoryActor updateRideHailStats
    TNCIterationStats(
      rideHailStats.map { case (k, v) => (k, v.toList) },
      beamServices.beamScenario.tazTreeMap,
      timeBinSizeInSec,
      numberOfTimeBins
    )

    clearStats()
  }

  private def updateStatsForIdlingVehicles(): Unit = {

    rideHailLastEvent.foreach(rhEvent => {
      val vehicleId = rhEvent._1
      var idlingBins = vehicleIdlingBins.get(vehicleId) match {
        case Some(bins) => bins
        case None =>
          val bins = mutable.Map[Int, String]()
          vehicleIdlingBins.put(vehicleId, bins)
          bins
      }

      val lastEvent = rhEvent._2
      val endTazId = getEndTazId(lastEvent)
      val endTime = lastEvent.arrivalTime
      val endingBin = getTimeBin(endTime)
      idlingBins ++= ((endingBin + 1) until numberOfTimeBins)
        .map((_, endTazId))
        .toMap
    })

    addMissingTaz()

    rideHailStats.foreach { items =>
      val tazId = items._1
      val bins = items._2

      bins.zipWithIndex.foreach { bin =>
        val binIndex = bin._2
        val noOfIdlingVehicles = getNoOfIdlingVehicle(tazId, binIndex)

        if (noOfIdlingVehicles > 0) {
          bins(binIndex) = bin._1 match {
            case Some(entry) =>
              Some(entry.copy(sumOfIdlingVehicles = noOfIdlingVehicles))
            case None =>
              Some(RideHailStatsEntry(sumOfIdlingVehicles = noOfIdlingVehicles))
          }
        }
      }
    }

    logIdlingStats()
  }

  private def addMissingTaz(): Unit = {
    val remainingTaz =
      vehicleIdlingBins
        .flatMap(_._2.values)
        .filter(!rideHailStats.contains(_))
        .toSet
    rideHailStats ++= remainingTaz.map(
      _ -> mutable.ArrayBuffer
        .fill[Option[RideHailStatsEntry]](numberOfTimeBins)(None)
    )
  }

  private def logIdlingStats(): Unit = {
    neverMovedVehicles = vehicles.collect { case (id, count) if count == -1 => id }.toVector

    // -1 : Vehicles never encountered any ride hail path traversal
    val numAlwaysIdleVehicles = neverMovedVehicles.size
    // 0 : Vehicles with ride hail path traversal but num_pass were 0 (zero)
    val numIdleVehiclesWithoutPassenger = vehicles.count(_._2 == 0)
    // Ride Hail Vehicles that never encounter any path traversal with some passenger
    // val numIdleVehicles = vehicles.count(_._2 < 1)

    logger.info(
      "{} rideHail vehicles (out of {}) were never moved and {} vehicles were moved without a passenger, during whole day.",
      numAlwaysIdleVehicles,
      vehicles.size,
      numIdleVehiclesWithoutPassenger
    )
//    logger.info(
//      "Ride hail vehicles with no passengers: {}",
//      vehicles.filter(_._2 == 0).keys.mkString(", ")
//    )
//    logger.info(
//      "Ride hail vehicles that never moved: {}",
//      vehicles.filter(_._2 == -1).keys.mkString(", ")
//    )
  }

  private def getNoOfIdlingVehicle(tazId: String, binIndex: Int): Int = {
    vehicleIdlingBins.count(vehicle => {
      val bins = vehicle._2
      bins.get(binIndex) match {
        case Some(taz) if taz == tazId => true
        case _                         => false
      }
    })
  }

  private def getEndTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val x = pathTraversalEvent.endX
    val y = pathTraversalEvent.endY
    val coord = beamServices.geo.wgs2Utm(new Coord(x, y))
    val tazId = getTazId(coord)
    tazId
  }

  private def getTazId(coord: Coord): String =
    Try(beamServices.beamScenario.tazTreeMap.getTAZ(coord.getX, coord.getY).tazId.toString).getOrElse("0")

  private def getTimeBin(time: Double): Int = (time / timeBinSizeInSec).toInt

  private def clearStats(): Unit = {
    rideHailStats = Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    rideHailModeChoiceEvents.clear()

    rideHailLastEvent.clear()

    vehicleIdlingBins.clear()

    rideHailEventsTuples.clear()

    vehicles.clear()
  }

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {

      case ModeChoiceEvent.EVENT_TYPE =>
        collectModeChoiceEvents(ModeChoiceEvent.apply(event))

      case PersonEntersVehicleEvent.EVENT_TYPE =>
        collectPersonEntersEvents(event.asInstanceOf[PersonEntersVehicleEvent])

      case PathTraversalEvent.EVENT_TYPE =>
        calculateStats(event.asInstanceOf[PathTraversalEvent])

      case ActivityEndEvent.EVENT_TYPE =>
        calculateActivityEndStats(event.asInstanceOf[ActivityEndEvent])
      case _ =>
    }
  }

  def isSameCoords(currentEvent: PathTraversalEvent, lastEvent: PathTraversalEvent): Boolean = {
    val lastCoord = (
      lastEvent.endX,
      lastEvent.endY
    )
    val currentCoord = (
      currentEvent.startX,
      currentEvent.startY
    )
    lastCoord == currentCoord
  }

  /*
    Calculating sumOfRides

    collectRides
    1. This method will collect all the ModeChoice events where the mode is 'rideHail'
    2. Afterwards when a PathTraversal event occurs for the same vehicle with num_passengers = 1 we will find the tazId
      using coord from the PathTraversal event
   */
  private def collectModeChoiceEvents(event: ModeChoiceEvent): Unit = {
    val mode = event.getMode
    if (mode.equals("ride_hail")) {
      val personId = event.getPersonId.toString
      rideHailModeChoiceEvents.put(personId, event)
    }
  }

  private def collectPersonEntersEvents(
    personEntersVehicleEvent: PersonEntersVehicleEvent
  ): Unit = {
    val personId = personEntersVehicleEvent.getPersonId.toString
    val vehicleId = personEntersVehicleEvent.getVehicleId.toString
    if (vehicleId.contains("rideHail")) {
      if (personId.contains("rideHailAgent") && !vehicles.contains(vehicleId))
        vehicles.put(vehicleId, -1)

      rideHailModeChoiceEvents.get(personId) match {
        case Some(modeChoiceEvent) =>
          rideHailEventsTuples.put(vehicleId, (modeChoiceEvent, personEntersVehicleEvent))
          rideHailModeChoiceEvents.remove(personId)
        case None =>
      }
    }
  }

  private def calculateStats(event: PathTraversalEvent): Unit = {

    processPathTraversalEvent(event)

    val mode = event.mode.value
    val vehicleId = event.vehicleId.toString
    val numPass = event.numberOfPassengers

    if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")) {
      if (numPass > 0) {
        vehicles.put(vehicleId, 1)
      } else if (vehicles.getOrElse(vehicleId, -1) < 0) {
        vehicles.put(vehicleId, 0)
      }
      collectIdlingVehicles(vehicleId, event)
    }
  }

  private def processPathTraversalEvent(pathTraversalEvent: PathTraversalEvent): Unit = {
    val vehicleId = pathTraversalEvent.vehicleId.toString
    val numPassengers = pathTraversalEvent.numberOfPassengers

    rideHailEventsTuples.get(vehicleId) match {
      case Some((modeChoiceEvent, personEntersVehicleEvent)) =>
        val startTime = modeChoiceEvent.getTime.toLong
        val endTime = personEntersVehicleEvent.getTime.toLong
        val waitingTime = endTime - startTime

        val binIndex = getTimeBin(startTime)
        val tazId = getStartTazId(pathTraversalEvent)

        val tazBins = rideHailStats.get(tazId) match {
          case Some(bins) => bins
          case None =>
            val bins = mutable.ArrayBuffer
              .fill[Option[RideHailStatsEntry]](numberOfTimeBins)(None)
            rideHailStats += (tazId -> bins)
            bins
        }

        tazBins(binIndex) = tazBins(binIndex) match {
          case Some(entry) =>
            val sumOfWaitingTimes =
              if (numPassengers > 0) entry.sumOfWaitingTimes + waitingTime
              else entry.sumOfWaitingTimes
            val numOfRequestedRides = entry.sumOfRequestedRides + 1
            Some(
              entry.copy(
                sumOfRequestedRides = numOfRequestedRides,
                sumOfWaitingTimes = sumOfWaitingTimes
              )
            )
          case None =>
            Some(RideHailStatsEntry(1, waitingTime))
        }

        rideHailEventsTuples.remove(vehicleId)

      case None =>
    }
  }

  private def collectIdlingVehicles(vehicleId: String, currentEvent: PathTraversalEvent) = {
    val startX = currentEvent.startX
    val startY = currentEvent.startY
    val coord = beamServices.geo.wgs2Utm(new Coord(startX, startY))
    val startTazId = getStartTazId(currentEvent)
    val endTazId = getEndTazId(currentEvent)

    val startTime = currentEvent.departureTime
    val endTime = currentEvent.arrivalTime

    val startBin = getTimeBin(startTime)
    val endingBin = getTimeBin(endTime)

//    logger.debug(
//      "startTazId({}), endTazId({}), startBin({}), endingBin({}), numberOfPassengers({})",
//      startTazId,
//      endTazId,
//      startBin,
//      endingBin,
//      currentEvent.getAttributes
//        .get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)
//    )

    var idlingBins = vehicleIdlingBins.get(vehicleId) match {
      case Some(bins) => bins
      case None =>
        val bins = mutable.Map[Int, String]()
        vehicleIdlingBins.put(vehicleId, bins)
        bins
    }

    val iB = rideHailLastEvent.get(vehicleId) match {

      case Some(lastEvent) =>
        val endTazId = getEndTazId(lastEvent)
        val endTime = lastEvent.arrivalTime
        val endingBin = getTimeBin(endTime)
        ((endingBin + 1) until startBin).map((_, endTazId)).toMap

      case None =>
        logger.debug("{} -> {} -> {}", vehicleId, startTazId, coord)

        if (startBin > 0) {
          (0 until startBin).map((_, startTazId)).toMap
        } else {
          Map[Int, String]()
        }
    }
    idlingBins ++= iB

    rideHailLastEvent.put(vehicleId, currentEvent)
  }

  private def getStartTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val startX = pathTraversalEvent.startX
    val startY = pathTraversalEvent.startY
    val coord = beamServices.geo.wgs2Utm(new Coord(startX, startY))
    val tazId = getTazId(coord)
    tazId
  }

  private def calculateActivityEndStats(event: ActivityEndEvent): Unit = {
    val linkId = event.getLinkId.toString.toInt
    val endPointLocation = GeoUtils.getR5EdgeCoord(linkId, transportNetwork)
    val coord = new Coord(endPointLocation.getX, endPointLocation.getY)
    val tazId = getTazId(coord)
    val binIndex = getTimeBin(event.getTime)

    val tazBins = rideHailStats.get(tazId) match {
      case Some(bins) => bins
      case None =>
        val bins = mutable.ArrayBuffer
          .fill[Option[RideHailStatsEntry]](numberOfTimeBins)(None)
        rideHailStats += (tazId -> bins)
        bins
    }

    tazBins(binIndex) = tazBins(binIndex) match {
      case Some(entry) =>
        val sumOfActivityEndEvents = entry.sumOfActivityEndEvents + 1
        Some(entry.copy(sumOfActivityEndEvents = sumOfActivityEndEvents))
      case None =>
        Some(RideHailStatsEntry(sumOfActivityEndEvents = 1))
    }
  }
}
