package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailIterationHistoryActor.UpdateRideHailStats
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.BeamServices
import beam.utils.GeoUtils
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.{ActivityEndEvent, Event, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.misc.Time
import org.slf4j.LoggerFactory

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

  def aggregate(rideHailStats: List[Option[RideHailStatsEntry]]): RideHailStatsEntry =
    rideHailStats.flatten.reduceOption(aggregate).getOrElse(empty)
}

class TNCIterationsStatsCollector(
  eventsManager: EventsManager,
  beamServices: BeamServices,
  rideHailIterationHistoryActor: ActorRef,
  transportNetwork: TransportNetwork
) extends BasicEventHandler {
  private val log =
    LoggerFactory.getLogger(classOf[TNCIterationsStatsCollector])

  private val beamConfig = beamServices.beamConfig
  // TAZ level -> how to get as input here?
  private val mTazTreeMap = Try(TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)).toOption

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

  def tellHistoryToRideHailIterationHistoryActorAndReset(): Unit = {
    updateStatsForIdlingVehicles()

    rideHailIterationHistoryActor ! UpdateRideHailStats(
      TNCIterationStats(
        rideHailStats.mapValues(_.toList),
        mTazTreeMap.get,
        timeBinSizeInSec,
        numberOfTimeBins
      )
    )

    clearStats()
  }

  override def handleEvent(event: Event): Unit = {

    event.getEventType match {

      case ModeChoiceEvent.EVENT_TYPE =>
        collectModeChoiceEvents(ModeChoiceEvent.apply(event))

      case PersonEntersVehicleEvent.EVENT_TYPE =>
        collectPersonEntersEvents(event.asInstanceOf[PersonEntersVehicleEvent])

      case PathTraversalEvent.EVENT_TYPE =>
        calculateStats(PathTraversalEvent.apply(event))

      case ActivityEndEvent.EVENT_TYPE =>
        calculateActivityEndStats(event.asInstanceOf[ActivityEndEvent])
      case _ =>
    }
  }

  /*
    Calculating sumOfRides

    collectRides
    1. This method will collect all the ModeChoice events where the mode is 'rideHailing'
    2. Afterwards when a PathTraversal event occurs for the same vehicle with num_passengers = 1 we will find the tazId
      using coord from the PathTraversal event
   */
  private def collectModeChoiceEvents(event: ModeChoiceEvent): Unit = {
    val attr = event.getAttributes
    val mode = attr.get(ModeChoiceEvent.ATTRIBUTE_MODE)

    if (mode.equals("ride_hail")) {

      val personId = attr.get(ModeChoiceEvent.ATTRIBUTE_PERSON_ID)
      rideHailModeChoiceEvents.put(personId, event)
    }
  }

  private def collectPersonEntersEvents(
    personEntersVehicleEvent: PersonEntersVehicleEvent
  ): Unit = {

    val attr = personEntersVehicleEvent.getAttributes
    val personId = attr.get(PersonEntersVehicleEvent.ATTRIBUTE_PERSON)
    val vehicleId = attr.get(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE)

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

  private def calculateStats(pathTraversalEvent: PathTraversalEvent): Unit = {

    processPathTraversalEvent(pathTraversalEvent)

    val attr = pathTraversalEvent.getAttributes
    val mode = attr.get(PathTraversalEvent.ATTRIBUTE_MODE)
    val vehicleId = attr.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
    val numPass = attr.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt

    if (mode.equalsIgnoreCase("car") && vehicleId.contains("rideHail")) {
      if (numPass > 0) {
        vehicles.put(vehicleId, 1)
      } else if (vehicles.getOrElse(vehicleId, -1) < 0) {
        vehicles.put(vehicleId, 0)
      }
      collectIdlingVehicles(vehicleId, pathTraversalEvent)
    }
  }

  private def processPathTraversalEvent(pathTraversalEvent: PathTraversalEvent): Unit = {

    val attr = pathTraversalEvent.getAttributes
    val vehicleId = attr.get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID)
    val numPassengers = attr.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS).toInt

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

    val startX =
      currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X)
        .toDouble
    val startY =
      currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y)
        .toDouble

    val coord = beamServices.geo.wgs2Utm(new Coord(startX, startY))

    val startTazId = getStartTazId(currentEvent)
    val endTazId = getEndTazId(currentEvent)

    val startTime =
      currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_DEPARTURE_TIME)
        .toLong
    val endTime = currentEvent.getAttributes
      .get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)
      .toLong

    val startBin = getTimeBin(startTime)
    val endingBin = getTimeBin(endTime)

    log.debug(
      s"startTazId($startTazId), endTazId($endTazId), startBin($startBin), endingBin($endingBin), numberOfPassengers(${currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_NUM_PASS)})"
    )

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
        val endTime = lastEvent.getAttributes
          .get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)
          .toLong
        val endingBin = getTimeBin(endTime)
        ((endingBin + 1) until startBin).map((_, endTazId)).toMap

      case None =>
        log.debug(s"$vehicleId -> $startTazId -> $coord")

        if (startBin > 0) {
          (0 until startBin).map((_, startTazId)).toMap
        } else {
          Map[Int, String]()
        }
    }
    idlingBins ++= iB

    rideHailLastEvent.put(vehicleId, currentEvent)
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
      val endTime = lastEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME)
        .toLong
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
    // -1 : Vehicles never encountered any ride hail path traversal
    val numAlwaysIdleVehicles = vehicles.count(_._2 == -1)
    // 0 : Vehicles with ride hail path traversal but num_pass were 0 (zero)
    val numIdleVehiclesWithoutPassenger = vehicles.count(_._2 == 0)
    // Ride Hail Vehicles that never encounter any path traversal with some passenger
    // val numIdleVehicles = vehicles.count(_._2 < 1)

    log.info(
      s"$numAlwaysIdleVehicles rideHail vehicles (out of ${vehicles.size}) were never moved and $numIdleVehiclesWithoutPassenger vehicles were moved without a passenger, during whole day."
    )
    log.info(
      s"Ride hail vehicles with no passengers: ${vehicles.filter(_._2 == 0).keys.mkString(", ")}"
    )
    log.info(
      s"Ride hail vehicles that never moved: ${vehicles.filter(_._2 == -1).keys.mkString(", ")}"
    )
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

  private def clearStats(): Unit = {
    rideHailStats = Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    rideHailModeChoiceEvents.clear()

    rideHailLastEvent.clear()

    vehicleIdlingBins.clear()

    rideHailEventsTuples.clear()

    vehicles.clear()
  }

  private def isSameCoords(currentEvent: PathTraversalEvent, lastEvent: PathTraversalEvent) = {
    val lastCoord = (
      lastEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X)
        .toDouble,
      lastEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y)
        .toDouble
    )
    val currentCoord = (
      currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X)
        .toDouble,
      currentEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y)
        .toDouble
    )
    lastCoord == currentCoord
  }

  private def getStartTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val startX =
      pathTraversalEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_X)
        .toDouble
    val startY =
      pathTraversalEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_START_COORDINATE_Y)
        .toDouble

    val coord = beamServices.geo.wgs2Utm(new Coord(startX, startY))

    val tazId = getTazId(coord)
    tazId
  }

  private def getEndTazId(pathTraversalEvent: PathTraversalEvent): String = {
    val x =
      pathTraversalEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_X)
        .toDouble
    val y =
      pathTraversalEvent.getAttributes
        .get(PathTraversalEvent.ATTRIBUTE_END_COORDINATE_Y)
        .toDouble

    val coord = beamServices.geo.wgs2Utm(new Coord(x, y))

    val tazId = getTazId(coord)
    tazId
  }

  private def getTazId(coord: Coord): String =
    mTazTreeMap.get.getTAZ(coord.getX, coord.getY).tazId.toString

  private def getTimeBin(time: Double): Int = (time / timeBinSizeInSec).toInt

  private def calculateActivityEndStats(event: ActivityEndEvent): Unit = {

    val attrs = event.getAttributes
    val linkId = attrs.get(ActivityEndEvent.ATTRIBUTE_LINK).toInt
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
