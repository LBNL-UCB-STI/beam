package beam.analysis.cartraveltime

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Calibration.StudyArea
import beam.utils.csv.CsvWriter
import beam.utils.{EventReader, NetworkHelper, NetworkHelperImpl, Statistics}
import com.typesafe.scalalogging.LazyLogging
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.{CategoryDataset, DefaultCategoryDataset}
import org.jfree.data.general.DatasetUtilities
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.controler.events.{IterationEndsEvent, ShutdownEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, ShutdownListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

class CarTripStatsFromPathTraversalEventHandler(
  val networkHelper: NetworkHelper,
  val controllerIO: OutputDirectoryHierarchy,
  val tripFilter: TripFilter,
  val filePrefix: String,
  val treatMismatchAsWarning: Boolean
) extends LazyLogging
    with IterationEndsListener
    with BasicEventHandler
    with ShutdownListener {

  import CarTripStatsFromPathTraversalEventHandler._

  private val prefix: String = if (filePrefix == "") filePrefix else filePrefix + "."
  private val secondsInHour = 3600

  private val freeFlowTravelTimeCalc: FreeFlowTravelTime = new FreeFlowTravelTime

  private val iterationsCarTripInfo: mutable.Map[(Int, CarType), IterationCarTripStats] = mutable.Map.empty

  private val averageCarSpeedPerIterationByType: collection.mutable.MutableList[Map[CarType, Double]] =
    collection.mutable.MutableList.empty

  private val statsHeader: Array[String] =
    Array("iteration", "carType", "avg", "median", "p75", "p95", "p99", "min", "max", "sum")

  private val travelTimeStatsWriter = {
    val fileName = controllerIO.getOutputFilename(s"${prefix}CarTravelTime.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val travelDistanceStatsWriter = {
    val fileName = controllerIO.getOutputFilename(s"${prefix}CarTravelDistance.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val travelSpeedStatsWriter = {
    val fileName = controllerIO.getOutputFilename(s"${prefix}CarSpeed.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val freeFlowTravelTimeStatsWriter = {
    val fileName = controllerIO.getOutputFilename(s"${prefix}FreeFlowCarTravelTime.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val freeFlowTravelSpeedStatsWriter = {
    val fileName = controllerIO.getOutputFilename(s"${prefix}FreeFlowCarSpeed.csv")
    new CsvWriter(fileName, statsHeader)
  }

  private val toClose: List[AutoCloseable] = List(
    travelTimeStatsWriter,
    travelDistanceStatsWriter,
    travelSpeedStatsWriter,
    freeFlowTravelTimeStatsWriter,
    freeFlowTravelSpeedStatsWriter
  )

  private val carType2PathTraversals: mutable.Map[CarType, ArrayBuffer[PathTraversalEvent]] =
    mutable.HashMap().withDefault(_ => ArrayBuffer.empty)

  override def handleEvent(event: Event): Unit = {
    event match {
      case pte: PathTraversalEvent if pte.mode == BeamMode.CAR && tripFilter.considerPathTraversal(pte) =>
        if (isCav(pte))
          carType2PathTraversals(CarType.CAV) = carType2PathTraversals(CarType.CAV) += pte
        else if (isRideHail(pte))
          carType2PathTraversals(CarType.RideHail) = carType2PathTraversals(CarType.RideHail) += pte
        else
          carType2PathTraversals(CarType.Personal) = carType2PathTraversals(CarType.Personal) += pte
      case _ =>
    }
  }

  private def isRideHail(pte: PathTraversalEvent): Boolean = {
    pte.vehicleId.toString.startsWith("rideHailVehicle")
  }

  private def isCav(pte: PathTraversalEvent): Boolean = {
    pte.vehicleType == "CAV"
  }

  def calcRideStats(iterationNumber: Int, carType: CarType): Seq[CarTripStat] = {
    val carPtes = carType2PathTraversals.getOrElse(carType, Seq.empty)

    val stats = carType match {
      case CarType.Personal =>
        val drivingWithParkingPtes = buildDrivingParking(carPtes, treatMismatchAsWarning = treatMismatchAsWarning)
        buildPersonalTripStats(
          networkHelper,
          freeFlowTravelTimeCalc,
          drivingWithParkingPtes,
          treatMismatchAsWarning = treatMismatchAsWarning
        )
      case _ => buildRideHailAndCavTripStats(networkHelper, freeFlowTravelTimeCalc, carPtes)
    }
    logger.info(
      s"$prefix For the iteration $iterationNumber created ${stats.length} trip stats for $carType from ${carPtes.size} PathTraversalEvents"
    )
    stats
  }

  def getIterationCarRideStats(iterationNumber: Int, rideStats: Seq[CarTripStat]): IterationCarTripStats = {
    buildStatistics(iterationNumber, rideStats)
  }

  private def createCarRideIterationGraph(
    iterationNumber: Int,
    rideStats: Seq[CarTripStat],
    mode: String
  ): Unit = {

    createIterationGraphForAverageSpeed(rideStats, iterationNumber, mode)

    createIterationGraphForAverageSpeedPercent(rideStats, iterationNumber, mode)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val type2RideStats: Map[CarType, Seq[CarTripStat]] = carType2PathTraversals.keys
      .map { carType =>
        carType -> calcRideStats(event.getIteration, carType)
      }
      .toSeq
      .sortBy(_._1)
      .toMap

    type2RideStats.foreach { case (carType, stats) =>
      writeCarTripStats(event.getIteration, stats, carType)
      createCarRideIterationGraph(event.getIteration, stats, carType.toString)
    }

    val type2Statistics: Map[CarType, IterationCarTripStats] = type2RideStats.mapValues { singleRideStats =>
      getIterationCarRideStats(event.getIteration, singleRideStats)
    }

    iterationsCarTripInfo ++= type2Statistics.map { case (carType, iterationCarTripStats) =>
      (event.getIteration, carType) -> iterationCarTripStats
    }

    averageCarSpeedPerIterationByType += type2Statistics.mapValues(_.speed.stats.avg)

    createRootGraphForAverageCarSpeedByType(event)
    createPercentageFreeSpeedGraph(event.getServices.getControlerIO.getOutputFilename("percentageFreeSpeed.png"))

    // write the iteration level car ride stats to output file
    type2Statistics.foreach { case (carType, stats) =>
      writeIterationCarRideStats(event, carType, stats)
    }

    writeAverageCarSpeedByTypes(event)

    carType2PathTraversals.clear()
  }

  private def createPercentageFreeSpeedGraph(
    outputFileName: String
  ): Unit = {
    val dataset = createPercentageFreeSpeedDataset()

    val chart = ChartFactory.createBarChart(
      "Percentage of speed from freeSpeed graph",
      "Iteration",
      "%",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      true,
      false
    )

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      outputFileName,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  private def createPercentageFreeSpeedDataset(): CategoryDataset = {
    val dataset = new DefaultCategoryDataset

    iterationsCarTripInfo.view
      .map { case (key, stat) => key -> stat.speed.stats.avg * 100 / stat.freeFlowSpeed.stats.avg }
      .foreach { case ((iteration, carType), percentage) =>
        dataset.addValue(percentage, carType, iteration)
      }

    dataset
  }

  /**
    * Create graph for average car speed for every type + average of all in root folder
    *
    * @param event IterationEndsEvent
    */
  private def createRootGraphForAverageCarSpeedByType(event: IterationEndsEvent): Unit = {
    val dataset = new DefaultCategoryDataset

    executeOnAverageSpeedData({ case (it, carType, speed) => dataset.addValue(speed, carType, it) })

    val chart = GraphUtils.createLineChartWithDefaultSettings(
      dataset,
      "Average car speed",
      "Iteration",
      "m / s",
      true,
      true
    )

    val plot = chart.getCategoryPlot;
    GraphUtils.plotLegendItemsWithColors(
      plot,
      dataset.getRowKeys.asInstanceOf[java.util.List[String]],
      GraphUtils.carTypesColors
    );
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      controllerIO.getOutputFilename(s"${prefix}AverageCarSpeed.png"),
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  /**
    * Write csv containing average car speed by types
    *
    * @param event IterationEndsEvent
    */
  private def writeAverageCarSpeedByTypes(event: IterationEndsEvent): Unit = {
    val outputPath = controllerIO.getOutputFilename(s"${prefix}AverageCarSpeed.csv")
    val csvWriter =
      new CsvWriter(outputPath, Vector("iteration", "car_type", "speed"))
    try {
      executeOnAverageSpeedData({ case (it, carType, speed) => csvWriter.write(it, carType, speed) })
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Writing average car speed to the $outputPath has failed with: ${ex.getMessage}", ex)
    } finally {
      Try(csvWriter.close())
    }
  }

  private def executeOnAverageSpeedData(execute: (Int, String, Double) => Unit): Unit = {
    averageCarSpeedPerIterationByType.zipWithIndex
      .foreach { case (type2Speed, iteration) =>
        val average = if (type2Speed.values.isEmpty) 0.0 else type2Speed.values.sum / type2Speed.values.size
        execute(iteration, "Average", average)

        type2Speed.foreach { case (carType, speed) =>
          execute(iteration, carType.toString, speed)
        }
      }

  }

  /**
    * Plots graph for average travel times per hour at iteration level
    *
    * @param trips Sequence of car trips
    * @param iterationNumber iteration number
    * @mode Mode of the trip
    */
  private def createIterationGraphForAverageSpeed(
    trips: Seq[CarTripStat],
    iterationNumber: Int,
    mode: String
  ): Unit = {
    val hourAverageSpeed = trips.groupBy(stats => stats.departureTime.toInt / secondsInHour).map {
      case (hour, statsList) => hour -> (statsList.map(_.speed).sum / statsList.size)
    }
    val maxHour = hourAverageSpeed.keys.max
    val averageSpeed = (0 until maxHour).map(hourAverageSpeed.getOrElse(_, 0.0))

    // generate the category dataset using the average travel times data
    val dataset = DatasetUtilities.createCategoryDataset("car", "", Array(averageSpeed.toArray))

    val fileName = s"${prefix}AverageSpeed.$mode.png"
    val graphTitle = s"Average Speed [ $mode ]"
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      graphTitle,
      "hour",
      "Average Speed [m/s]",
      false
    )
    val plot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, dataset.getRowCount)
    val graphImageFile = controllerIO.getIterationFilename(iterationNumber, fileName)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  private def createIterationGraphForAverageSpeedPercent(
    trips: Seq[CarTripStat],
    iterationNumber: Int,
    mode: String
  ): Unit = {
    val hourAverageSpeedPercent =
      trips.groupBy(stats => stats.departureTime.toInt / secondsInHour).map { case (hour, statsList) =>
        val avgSpeed = statsList.map(_.speed).sum / statsList.size
        val avgFreeFlowSpeed = statsList.map(_.freeFlowSpeed).sum / statsList.size
        hour -> 100 * (avgSpeed / avgFreeFlowSpeed)
      }
    val arr = (0 until hourAverageSpeedPercent.keys.max).map(hourAverageSpeedPercent.getOrElse(_, 0.0))
    val dataset = DatasetUtilities.createCategoryDataset("car", "", Array(arr.toArray))
    val fileName = s"${prefix}AverageSpeedPercentage.$mode.png"
    val graphTitle = s"Average Speed Percentage [ $mode ]"
    val chart = GraphUtils.createStackedBarChartWithDefaultSettings(
      dataset,
      graphTitle,
      "hour",
      "Average Speed Percentage",
      false
    )
    val plot = chart.getCategoryPlot
    GraphUtils.plotLegendItems(plot, dataset.getRowCount)
    val graphImageFile = controllerIO.getIterationFilename(iterationNumber, fileName)
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      graphImageFile,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  private def writeCarTripStats(
    iterationNumber: Int,
    rideStats: Seq[CarTripStat],
    carType: CarType
  ): Unit = {
    val carTypeFilename = s"$carType".toLowerCase
    val outputPath =
      controllerIO.getIterationFilename(iterationNumber, s"${prefix}CarRideStats.$carTypeFilename.csv.gz")

    val csvWriter =
      new CsvWriter(
        outputPath,
        Vector(
          "vehicle_id",
          "carType",
          "travel_time",
          "distance",
          "free_flow_travel_time",
          "departure_time",
          "start_x",
          "start_y",
          "end_x",
          "end_y"
        )
      )
    try {
      rideStats.foreach { stat =>
        csvWriter.write(
          stat.vehicleId,
          carType.toString,
          stat.travelTime,
          stat.distance,
          stat.freeFlowTravelTime,
          stat.departureTime,
          stat.startCoordWGS.getX,
          stat.startCoordWGS.getY,
          stat.endCoordWGS.getX,
          stat.endCoordWGS.getY
        )
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Writing ride stats to the $outputPath has failed with: ${ex.getMessage}", ex)
    } finally {
      Try(csvWriter.close())
    }
  }

  override def notifyShutdown(event: ShutdownEvent): Unit = {
    toClose.foreach(c => Try(c.close()))
  }

  private def writeIterationCarRideStats(
    event: IterationEndsEvent,
    carType: CarType,
    carRideStatistics: IterationCarTripStats
  ): Unit = {
    // Write car travel time stats to CSV
    writeStats(travelTimeStatsWriter, carType, event.getIteration, carRideStatistics.travelTime.stats)
    // Write car travel distance stats to CSV
    writeStats(travelDistanceStatsWriter, carType, event.getIteration, carRideStatistics.distance.stats)
    // Write car travel speed stats to CSV
    writeStats(travelSpeedStatsWriter, carType, event.getIteration, carRideStatistics.speed.stats)
    // Write free flow car travel time stats to CSV
    writeStats(freeFlowTravelTimeStatsWriter, carType, event.getIteration, carRideStatistics.freeFlowTravelTime.stats)
    // Write free flow car speed stats to CSV
    writeStats(freeFlowTravelSpeedStatsWriter, carType, event.getIteration, carRideStatistics.freeFlowSpeed.stats)
  }

  private def writeStats(csvWriter: CsvWriter, carType: CarType, iteration: Int, statistics: Statistics): Unit = {
    try {
      csvWriter.write(
        iteration,
        carType.toString,
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
        logger.error(s"Could not write iteration $iteration stats $statistics. Error: ${ex.getMessage}", ex)
    }
  }
}

object CarTripStatsFromPathTraversalEventHandler extends LazyLogging {

  def eventsFilterWhenReadFromCsv(event: Event): Boolean = {
    event.getEventType == "PathTraversal"
  }

  def apply(
    pathToNetwork: String,
    eventsFilePath: String,
    controlerIO: OutputDirectoryHierarchy,
    studyAreaTripFilter: StudyAreaTripFilter,
    prefix: String,
    treatMismatchAsWarning: Boolean
  ): CarTripStatsFromPathTraversalEventHandler = {
    val network: Network = {
      val n = NetworkUtils.createNetwork()
      new MatsimNetworkReader(n)
        .readFile(pathToNetwork)
      n
    }

    val (ptesIter: Iterator[PathTraversalEvent], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilterWhenReadFromCsv)
      (
        e.map(PathTraversalEvent.apply(_))
          .filter(pte => pte.mode == BeamMode.CAR && !pte.vehicleId.toString.startsWith("rideHailVehicle")),
        c
      )
    }
    val r =
      new CarTripStatsFromPathTraversalEventHandler(
        new NetworkHelperImpl(network),
        controlerIO,
        studyAreaTripFilter,
        prefix,
        treatMismatchAsWarning = treatMismatchAsWarning
      )
    try {
      ptesIter.foreach(r.handleEvent)
      r
    } finally {
      Try(closable.close())
    }
  }

  def buildStatistics(
    iterationNumber: Int,
    rideStats: Seq[CarTripStat]
  ): IterationCarTripStats = {
    val travelTimeStas = TravelTimeStatistics(rideStats)
    val speedStats = WeightedSpeedStatistics(rideStats)
    val distanceStats = DistanceStatistics(Statistics(rideStats.map(_.distance)))
    val freeFlowTravelTimeStats = FreeFlowTravelTimeStatistics(rideStats)
    val freeFlowSpeedStats = FreeFlowSpeedStatistics(rideStats)
    IterationCarTripStats(
      iteration = iterationNumber,
      travelTime = travelTimeStas,
      speed = speedStats,
      distance = distanceStats,
      freeFlowTravelTime = freeFlowTravelTimeStats,
      freeFlowSpeed = freeFlowSpeedStats
    )
  }

  def calcFreeFlowDuration(freeFlowTravelTime: FreeFlowTravelTime, linkIds: IndexedSeq[Link]): Double = {
    linkIds.foldLeft(0.0) { case (acc, link) =>
      val t = freeFlowTravelTime.getLinkTravelTime(link, 0.0, null, null)
      acc + t
    }
  }

  private def buildPersonalTripStats(
    networkHelper: NetworkHelper,
    freeFlowTravelTimeCalc: FreeFlowTravelTime,
    drivingWithParkingPtes: Iterable[(PathTraversalEvent, PathTraversalEvent)],
    treatMismatchAsWarning: Boolean
  ): Seq[CarTripStat] = {
    val stats = drivingWithParkingPtes.foldLeft(List.empty[CarTripStat]) { case (acc, (driving, parking)) =>
      if (driving.arrivalTime != parking.departureTime && treatMismatchAsWarning) {
        val msg = s"arrivalTime != departureTime\n\tdriving: $driving\n\tparking: $parking"
        logger.warn(msg)
      }
      val travelTime =
        ((driving.arrivalTime - driving.departureTime) + (parking.arrivalTime - parking.departureTime)).toDouble
      // add the computed travel time to the list of travel times tracked during the hour
      val length = driving.legLength + parking.legLength

      // We start driving in the very end of the first link => so we we didn't actually travel that link, so we should drop it for both driving and parking
      val linkIds = (driving.linkIds.drop(1) ++ parking.linkIds.drop(1)).map(lid => networkHelper.getLinkUnsafe(lid))
      val freeFlowTravelTime: Double = calcFreeFlowDuration(freeFlowTravelTimeCalc, linkIds)
      val startCoordWGS = new Coord(driving.startX, driving.startY)
      val endCoordWGS = new Coord(parking.endX, parking.endY)
      CarTripStat(
        vehicleId = driving.vehicleId.toString,
        travelTime = travelTime,
        distance = length,
        freeFlowTravelTime = freeFlowTravelTime,
        departureTime = driving.departureTime,
        startCoordWGS = startCoordWGS,
        endCoordWGS = endCoordWGS
      ) :: acc
    }
    stats
  }

  private def buildRideHailAndCavTripStats(
    networkHelper: NetworkHelper,
    freeFlowTravelTimeCalc: FreeFlowTravelTime,
    ptes: Seq[PathTraversalEvent]
  ): Seq[CarTripStat] = {
    ptes.map { event =>
      val travelTime = event.arrivalTime - event.departureTime
      val length = event.legLength
      val linkIds = event.linkIds.map(lid => networkHelper.getLinkUnsafe(lid))
      // We start driving in the very end of the first link => so we we didn't actually travel that link, so we should drop it
      val freeFlowTravelTime: Double = calcFreeFlowDuration(freeFlowTravelTimeCalc, linkIds.drop(1))
      CarTripStat(
        event.vehicleId.toString,
        travelTime,
        length,
        freeFlowTravelTime,
        event.departureTime,
        startCoordWGS = new Coord(event.startX, event.startY),
        endCoordWGS = new Coord(event.endX, event.endY)
      )
    }
  }

  private def buildDrivingParking(
    ptes: Seq[PathTraversalEvent],
    treatMismatchAsWarning: Boolean
  ): Iterable[(PathTraversalEvent, PathTraversalEvent)] = {
    val grouped = ptes
      .groupBy(x => (x.vehicleId, x.driverId))
    val drivingWithParkingPtes = grouped.map { case ((vehId, driverId), xs) =>
      val sorted = xs.sortBy(x => x.departureTime)
      if (sorted.length % 2 == 1 && treatMismatchAsWarning) {
        logger.warn(
          s"Vehicle $vehId with driver $driverId has ${sorted.length} events, but expected to have odd number of events (1 driving PathTraversalEvent and 1 parking PathTraversalEvent)"
        )
      }
      sorted.sliding(2, 2).flatMap { ptes =>
        val maybeDriving = ptes.headOption
        val maybeParking = ptes.lift(1)
        for {
          driving <- maybeDriving
          parking <- maybeParking
        } yield (driving, parking)
      }
    }.flatten
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

    val studyArea = StudyArea(enabled = true, lat = 30.259504, lon = -97.7431187, radius = 20000)
    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val studyAreaTripFilter = new StudyAreaTripFilter(studyArea, geoUtils)

    val controlerIO: OutputDirectoryHierarchy =
      new OutputDirectoryHierarchy("", OverwriteFileSetting.failIfDirectoryExists)

    val c = CarTripStatsFromPathTraversalEventHandler(
      pathToNetwork = pathToNetwork,
      eventsFilePath = eventsFilePath,
      controlerIO = controlerIO,
      studyAreaTripFilter = studyAreaTripFilter,
      prefix = "studyarea",
      treatMismatchAsWarning = true
    )
    val rideStats = c.calcRideStats(iterationNumber, CarType.Personal)
    val iterationCarRideStats = c.getIterationCarRideStats(iterationNumber, rideStats)
    logger.info("IterationCarRideStats:")
    logger.info(s"travelTime: ${iterationCarRideStats.travelTime}")
    logger.info(s"speed: ${iterationCarRideStats.speed}")
    logger.info(s"length: ${iterationCarRideStats.distance}")
    logger.info(s"freeFlowTravelTime: ${iterationCarRideStats.freeFlowTravelTime}")
    logger.info(s"freeFlowSpeed: ${iterationCarRideStats.freeFlowSpeed}")

    c.notifyIterationEnds(new IterationEndsEvent(null, 10))
  }
}
