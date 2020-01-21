package beam.analysis.cartraveltime

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.utils.csv.CsvWriter
import beam.utils.{EventReader, NetworkHelper, NetworkHelperImpl, Statistics}
import com.google.common.base.CaseFormat
import com.typesafe.scalalogging.LazyLogging
import org.jfree.chart.JFreeChart
import org.jfree.chart.plot.CategoryPlot
import org.jfree.data.category.CategoryDataset
import org.jfree.data.general.DatasetUtilities
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
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
  private val averageTravelTimePerIteration: collection.mutable.MutableList[Long] =
    collection.mutable.MutableList.empty[Long]

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

  private val carPathTraversals: ArrayBuffer[PathTraversalEvent] = ArrayBuffer.empty

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if isCarAndNotRideHail(pte) =>
        carPathTraversals += pte
      case _ =>
    }
  }

  private def isCarAndNotRideHail(pte: PathTraversalEvent): Boolean = {
    pte.mode == BeamMode.CAR && !pte.vehicleId.toString.startsWith("rideHailVehicle")
  }

  def calcRideStats(iterationNumber: Int): Seq[SingleRideStat] = {
    getRideStats(networkHelper, freeFlowTravelTimeCalc, iterationNumber, carPathTraversals)
  }

  def getIterationCarRideStats(iterationNumber: Int, rideStats: Seq[SingleRideStat]): IterationCarRideStats = {
    buildStatistics(networkHelper, freeFlowTravelTimeCalc, iterationNumber, rideStats)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val rideStats: Seq[SingleRideStat] = calcRideStats(event.getIteration)
    writeRideStats(event.getIteration, rideStats)

    val carRideStatistics: IterationCarRideStats = getIterationCarRideStats(event.getIteration, rideStats)

    //save average travel time for the current iteration
    averageTravelTimePerIteration += java.util.concurrent.TimeUnit.SECONDS
      .toMinutes(carRideStatistics.travelTime.stats.avg.toLong)
    // generate average travel times graph at root level
    createRootGraphForAverageCarTravelTime(event)

    // group single ride stats by departure time ( in hours)
    val rideStatsGroupByDepartureTime = rideStats
      .map(r => (r.travelTime, java.util.concurrent.TimeUnit.SECONDS.toHours(r.departureTime.toLong)))
      .groupBy(_._2)
    // extract travel time from grouped stats
    val travelTimesByHourByCarMode: Map[Long, Seq[Double]] =
      rideStatsGroupByDepartureTime.map(entry => entry._1 -> entry._2.map(_._1))
    // Generate data set from travel times data above to plot graph
    val iterationGraphData = generateGraphDataForAverageTravelTimes(travelTimesByHourByCarMode)
    // Plot and save the graph for each iteration
    createIterationGraphForAverageCarTravelTime(iterationGraphData, event.getIteration)

    // write the iteration level car ride stats to output file
    writeIterationCarRideStats(event, carRideStatistics)

    carPathTraversals.clear()
  }

  /**
    * Generates category dataset used to generate graph at iteration level.
    * @return dataset for average travel times graph at iteration level
    */
  private def generateGraphDataForAverageTravelTimes(
    travelTimesByHour: Map[Long, Seq[Double]]
  ): CategoryDataset = {
    // For each hour in a day
    val averageTravelTimes = for (i <- 0 until 24) yield {
      // Compute the average of the travel times recorded for that hour
      val travelTimes = travelTimesByHour.getOrElse(i, List.empty[Double])
      // if no travel time recorded set average travel time to 0
      if (travelTimes.isEmpty)
        0D
      else {
        val avg = travelTimes.sum / travelTimes.length
        // convert the average travl time (in seconds) to minutes
        java.util.concurrent.TimeUnit.SECONDS.toMinutes(avg.toLong).toDouble
      }
    }
    // generate the category dataset using the average travel times data
    DatasetUtilities.createCategoryDataset("car", "", Array(averageTravelTimes.toArray))
  }

  /**
    * Plots graph for average travel times at root level
    * @param event IterationEndsEvent
    */
  private def createRootGraphForAverageCarTravelTime(event: IterationEndsEvent): Unit = {
    val graphData: Array[Array[Double]] = Array(averageTravelTimePerIteration.toArray.map(_.toDouble))
    val categoryDataset = DatasetUtilities.createCategoryDataset("car", "", graphData)
    val outputDirectoryHierarchy = event.getServices.getControlerIO
    val fileName = outputDirectoryHierarchy.getOutputFilename("averageCarTravelTimes" + ".png")
    val graphTitle = "Average Travel Time [" + "car" + "]"
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      categoryDataset,
      graphTitle,
      "Iteration",
      "Average Travel Time [min]",
      fileName,
      false
    )
    val plot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, categoryDataset.getRowCount)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      fileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  /**
    * Plots graph for average travel times per hour at iteration level
    * @param dataset category dataset for graph genration
    * @param iterationNumber iteration number
    */
  private def createIterationGraphForAverageCarTravelTime(dataset: CategoryDataset, iterationNumber: Int): Unit = {
    val fileName = "averageTravelTimesCar.png"
    val graphTitle = "Average Travel Time [ car ]"
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      graphTitle,
      "hour",
      "Average Travel Time [min]",
      fileName,
      false
    )
    val plot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, dataset.getRowCount)
    val graphImageFile = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getIterationFilename(iterationNumber, fileName)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
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
    event.getEventType == "PathTraversal"
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
        // get the hour of event
        val hour = java.util.concurrent.TimeUnit.SECONDS.toHours(driving.getTime.toLong)
        // add the computed travel time to the list of travel times tracked during the hour
        val length = driving.legLength + parking.legLength
        val linkIds = (driving.linkIds ++ parking.linkIds).map(lid => networkHelper.getLinkUnsafe(lid))
        val freeFlowTravelTime: Double = calcFreeFlowDuration(freeFlowTravelTimeCalc, linkIds)
        SingleRideStat(driving.vehicleId.toString, travelTime, length, freeFlowTravelTime, driving.departureTime) :: acc
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
            logger.warn(
              s"Vehicle $vehId with driver $driverId has ${sorted.length} events, but expected to have odd number of events (1 driving PathTraversalEvent and 1 parking PathTraversalEvent)"
            )
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
