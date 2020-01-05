package beam.analysis.cartraveltime

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.utils.csv.CsvWriter
import beam.utils.{EventReader, NetworkHelper, NetworkHelperImpl, Statistics}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

class CarRideStatsFromPathTraversalEventHandler(
  val networkHelper: NetworkHelper,
  val maybeControlerIO: Option[OutputDirectoryHierarchy]
) extends LazyLogging
    with IterationEndsListener
    with BasicEventHandler
    with ShutdownListener {
  import CarRideStatsFromPathTraversalEventHandler._

  private val freeFlowTravelTimeCalc: FreeFlowTravelTime = new FreeFlowTravelTime

  val statsHeader: Array[String] = Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max", "sum")

  private val maybeTravelTimeStatsWriter = maybeControlerIO.map { controlerIO =>
    val fileName = controlerIO.getOutputFilename("CarTravelTime.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val maybeTravelDistanceStatsWriter = maybeControlerIO.map { controlerIO =>
    val fileName = controlerIO.getOutputFilename("CarTravelDistance.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val maybeTravelSpeedStatsWriter = maybeControlerIO.map { controlerIO =>
    val fileName = controlerIO.getOutputFilename("CarSpeed.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val maybeFreeFlowTravelTimeStatsWriter = maybeControlerIO.map { controlerIO =>
    val fileName = controlerIO.getOutputFilename("FreeFlowCarTravelTime.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val maybeFreeFlowTravelSpeedStatsWriter = maybeControlerIO.map { controlerIO =>
    val fileName = controlerIO.getOutputFilename("FreeFlowCarSpeed.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val toClose: List[AutoCloseable] = List(
    maybeTravelTimeStatsWriter,
    maybeTravelDistanceStatsWriter,
    maybeTravelSpeedStatsWriter,
    maybeFreeFlowTravelTimeStatsWriter,
    maybeFreeFlowTravelSpeedStatsWriter
  ).flatten

  private val carPtes: ArrayBuffer[PathTraversalEvent] = ArrayBuffer.empty

  override def handleEvent(event: Event): Unit = {
    event match {

      case pte: PathTraversalEvent if isCarAndNotRideHail(pte) =>
        carPtes += pte
      case _ =>
    }
  }

  private def isCarAndNotRideHail(pte: PathTraversalEvent): Boolean = {
    pte.mode == BeamMode.CAR && !pte.vehicleId.toString.startsWith("rideHailVehicle")
  }

  def calcRideStats(iterationNumber: Int): Seq[SingleRideStat] = {
    getRideStats(networkHelper, freeFlowTravelTimeCalc, iterationNumber, carPtes)
  }

  def getIterationCarRideStats(iterationNumber: Int, rideStats: Seq[SingleRideStat]): IterationCarRideStats = {
    buildStatistics(networkHelper, freeFlowTravelTimeCalc, iterationNumber, rideStats)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val rideStats = calcRideStats(event.getIteration)
    writeRideStats(event.getIteration, rideStats)

    val carRideStatistics = getIterationCarRideStats(event.getIteration, rideStats)
    writeIterationCarRideStats(event, carRideStatistics)

    carPtes.clear()
  }

  private def writeRideStats(iterationNumber: Int, rideStats: Seq[SingleRideStat]): Unit = {
    val maybeOutputPath = maybeControlerIO.map(cio => cio.getIterationFilename(iterationNumber, "CarRideStats.csv.gz"))
    maybeOutputPath.foreach { outputPath =>
      val csvWriter =
        new CsvWriter(outputPath, Vector("vehicle_id", "travel_time", "distance", "free_flow_travel_time"))
      try {
        rideStats.foreach { stat =>
          csvWriter.write(stat.vehicleId, stat.travelTime, stat.distance, stat.freeFlowTravelTime)
        }
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Writing ride stats to the ${outputPath} has failed with: ${ex.getMessage}", ex)
      } finally {
        Try(csvWriter.close())
      }
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    toClose.foreach(c => Try(c.close()))
  }

  private def writeIterationCarRideStats(event: IterationEndsEvent, carRideStatistics: IterationCarRideStats): Unit = {
    // Write car travel time stats to CSV
    maybeTravelTimeStatsWriter.foreach(writeStats(_, event.getIteration, carRideStatistics.travelTime.stats))
    // Write car travel distance stats to CSV
    maybeTravelDistanceStatsWriter.foreach(writeStats(_, event.getIteration, carRideStatistics.distance.stats))
    // Write car travel speed stats to CSV
    maybeTravelSpeedStatsWriter.foreach(writeStats(_, event.getIteration, carRideStatistics.speed.stats))
    // Write free flow car travel time stats to CSV
    maybeFreeFlowTravelTimeStatsWriter.foreach(
      writeStats(_, event.getIteration, carRideStatistics.freeFlowTravelTime.stats)
    )
    // Write free flow car speed stats to CSV
    maybeFreeFlowTravelSpeedStatsWriter.foreach(
      writeStats(_, event.getIteration, carRideStatistics.freeFlowSpeed.stats)
    )
  }

  private def writeStats(csvWriter: CsvWriter, iteration: Int, statistics: Statistics): Unit = {
    try {
      csvWriter.write(
        iteration,
        statistics.avg,
        statistics.median,
        statistics.p75,
        statistics.p95,
        statistics.p99,
        statistics.minValue,
        statistics.maxValue,
        statistics.sum
      )
      csvWriter.flush()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write iteration $iteration stats ${statistics}. Error: ${ex.getMessage}", ex)
    }
  }
}

object CarRideStatsFromPathTraversalEventHandler extends LazyLogging {

  def eventsFilterWhenReadFromCsv(event: Event): Boolean = {
    // We need only PathTraversal
    val isNeededEvent = event.getEventType == "PathTraversal"
    isNeededEvent
  }

  def apply(pathToNetwork: String, eventsFilePath: String): CarRideStatsFromPathTraversalEventHandler = {
    val network: Network = {
      val n = NetworkUtils.createNetwork()
      new MatsimNetworkReader(n)
        .readFile(pathToNetwork)
      n
    }

    val (ptesIter: Iterator[PathTraversalEvent], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilterWhenReadFromCsv)
      (
        e.map(PathTraversalEvent.apply)
          .filter(pte => pte.mode == BeamMode.CAR && !pte.vehicleId.toString.startsWith("rideHailVehicle")),
        c
      )
    }
    val r = new CarRideStatsFromPathTraversalEventHandler(new NetworkHelperImpl(network), None)
    try {
      ptesIter.foreach(r.handleEvent)
      r
    } finally {
      Try(closable.close())
    }
  }

  def buildStatistics(
    networkHelper: NetworkHelper,
    freeFlowTravelTime: FreeFlowTravelTime,
    iterationNumber: Int,
    rideStats: Seq[SingleRideStat]
  ): IterationCarRideStats = {
    val travelTimeStas = TravelTimeStatistics(rideStats)
    val speedStats = SpeedStatistics(rideStats)
    val distanceStats = DistanceStatistics(Statistics(rideStats.map(_.distance)))
    val freeFlowTravelTimeStats = FreeFlowTravelTimeStatistics(rideStats)
    val freeFlowSpeedStats = FreeFlowSpeedStatistics(rideStats)
    IterationCarRideStats(
      iteration = iterationNumber,
      travelTime = travelTimeStas,
      speed = speedStats,
      distance = distanceStats,
      freeFlowTravelTime = freeFlowTravelTimeStats,
      freeFlowSpeed = freeFlowSpeedStats
    )
  }

  def getRideStats(
    networkHelper: NetworkHelper,
    freeFlowTravelTime: FreeFlowTravelTime,
    iterationNumber: Int,
    carPtes: Seq[PathTraversalEvent]
  ): Seq[SingleRideStat] = {
    val drivingWithParkingPtes = buildDrivingParking(carPtes)
    val stats = buildRideStats(networkHelper, freeFlowTravelTime, drivingWithParkingPtes)
    logger.info(
      s"For the iteration ${iterationNumber} created ${stats.length} ride stats from ${carPtes.size} PathTraversalEvents"
    )
    stats
  }

  def calcFreeFlowDuration(freeFlowTravelTime: FreeFlowTravelTime, linkIds: IndexedSeq[Link]): Double = {
    linkIds.foldLeft(0.0) {
      case (acc, link) =>
        val t = freeFlowTravelTime.getLinkTravelTime(link, 0.0, null, null)
        acc + t
    }
  }

  def buildRideStats(
    networkHelper: NetworkHelper,
    freeFlowTravelTimeCalc: FreeFlowTravelTime,
    drivingWithParkingPtes: Iterable[(PathTraversalEvent, PathTraversalEvent)]
  ): Seq[SingleRideStat] = {
    val stats = drivingWithParkingPtes.foldLeft(List.empty[SingleRideStat]) {
      case (acc, (driving, parking)) =>
        if (driving.arrivalTime != parking.departureTime) {
          val msg = s"arrivalTime != departureTime\n\tdriving: $driving\n\tparking: $parking"
          logger.warn(msg)
        }
        val travelTime =
          ((driving.arrivalTime - driving.departureTime) + (parking.arrivalTime - parking.departureTime)).toDouble
        val length = driving.legLength + parking.legLength
        val linkIds = (driving.linkIds ++ parking.linkIds).map(lid => networkHelper.getLinkUnsafe(lid))
        val freeFlowTravelTime: Double = calcFreeFlowDuration(freeFlowTravelTimeCalc, linkIds)
        SingleRideStat(driving.vehicleId.toString, travelTime, length, freeFlowTravelTime) :: acc
    }
    stats
  }

  def buildDrivingParking(ptes: Seq[PathTraversalEvent]): Iterable[(PathTraversalEvent, PathTraversalEvent)] = {
    val drivingWithParkingPtes = ptes
      .groupBy(x => (x.vehicleId, x.driverId))
      .map {
        case ((vehId, driverId), xs) =>
          val sorted = xs.sortBy(x => x.departureTime)
          if (sorted.length % 2 == 1) {
            logger.warn(s"Vehicle $vehId with driver $driverId has ${sorted.length} events")
          }
          sorted.sliding(2, 2).flatMap { ptes =>
            val maybeDriving = ptes.lift(0)
            val maybeParking = ptes.lift(1)
            for {
              driving <- maybeDriving
              parking <- maybeParking
            } yield (driving, parking)
          }
      }
      .flatten
    drivingWithParkingPtes
  }

  def main(args: Array[String]): Unit = {
    require(
      args.length == 3,
      "Expect 3 args. First argument should be the path to the network file. The second argument is the path to the events file. The third argument is an iteration number"
    )
    val pathToNetwork = args(0)
    val eventsFilePath = args(1)
    val iterationNumber = Try(args(2).toInt).toOption.getOrElse(-1)

    val c = CarRideStatsFromPathTraversalEventHandler(pathToNetwork, eventsFilePath)
    val rideStats = c.calcRideStats(iterationNumber)
    val iterationCarRideStats = c.getIterationCarRideStats(iterationNumber, rideStats)
    logger.info("IterationCarRideStats:")
    logger.info(s"travelTime: ${iterationCarRideStats.travelTime}")
    logger.info(s"speed: ${iterationCarRideStats.speed}")
    logger.info(s"length: ${iterationCarRideStats.distance}")
    logger.info(s"freeFlowTravelTime: ${iterationCarRideStats.freeFlowTravelTime}")
    logger.info(s"freeFlowSpeed: ${iterationCarRideStats.freeFlowSpeed}")
  }
}
