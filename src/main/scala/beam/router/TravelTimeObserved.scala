package beam.router
import java.awt.geom.Ellipse2D
import java.awt.{BasicStroke, Color}

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, WALK}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamScenario, BeamServices}
import beam.sim.common.GeoUtils
import beam.utils.{FileUtils, GeoJsonReader, ProfilingUtils}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Geometry
import org.jfree.chart.ChartFactory
import org.jfree.chart.annotations.{XYLineAnnotation, XYTextAnnotation}
import org.jfree.chart.plot.{PlotOrientation, XYPlot}
import org.jfree.data.statistics.{HistogramDataset, HistogramType}
import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import org.jfree.ui.RectangleInsets
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

class TravelTimeObserved @Inject()(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val geo: GeoUtils
) extends LazyLogging {
  import TravelTimeObserved._
  import beamScenario._

  @volatile
  private var skimmer: BeamSkimmer = new BeamSkimmer(beamServices, beamScenario, geo)

  private val observedTravelTimesOpt: Option[Map[PathCache, Float]] = {
    val zoneBoundariesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath
    val zoneODTravelTimesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath

    if (zoneBoundariesFilePath.nonEmpty && zoneODTravelTimesFilePath.nonEmpty) {
      val tazToMovId: Map[TAZ, Int] = buildTAZ2MovementId(
        zoneBoundariesFilePath,
        geo,
        tazTreeMap
      )
      val movId2Taz: Map[Int, TAZ] = tazToMovId.map { case (k, v) => v -> k }
      Some(buildPathCache2TravelTime(zoneODTravelTimesFilePath, movId2Taz))
    } else None
  }

  val uniqueModes: List[BeamMode.CAR.type] = List(CAR)

  val uniqueTimeBins: Range.Inclusive = 0 to 23

  val dummyId: Id[BeamVehicleType] = Id.create("NA", classOf[BeamVehicleType])

  def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): Unit = {
    val legs = trip.legs.filter(x => x.beamLeg.mode == BeamMode.CAR || x.beamLeg.mode == BeamMode.CAV)
    legs.foreach { carLeg =>
      val dummyHead = EmbodiedBeamLeg.dummyLegAt(
        carLeg.beamLeg.startTime,
        Id.createVehicleId(""),
        isLastLeg = false,
        carLeg.beamLeg.travelPath.startPoint.loc,
        WALK,
        dummyId
      )

      val dummyTail = EmbodiedBeamLeg.dummyLegAt(
        carLeg.beamLeg.endTime,
        Id.createVehicleId(""),
        isLastLeg = true,
        carLeg.beamLeg.travelPath.endPoint.loc,
        WALK,
        dummyId
      )
      // In case of `CAV` we have to override its mode to `CAR`
      val fixedCarLeg = if (carLeg.beamLeg.mode == BeamMode.CAV) {
        carLeg.copy(beamLeg = carLeg.beamLeg.copy(mode = BeamMode.CAR))
      } else {
        carLeg
      }
      val carTrip = EmbodiedBeamTrip(Vector(dummyHead, fixedCarLeg, dummyTail))
      skimmer.observeTrip(carTrip, generalizedTimeInHours, generalizedCost, energyConsumption)
    }
  }

  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    writeTravelTimeObservedVsSimulated(event)
    skimmer = new BeamSkimmer(beamServices, beamScenario, geo)
  }

  def writeTravelTimeObservedVsSimulated(event: IterationEndsEvent): Unit = {
    observedTravelTimesOpt.foreach { observedTravelTimes =>
      ProfilingUtils.timed(
        s"writeTravelTimeObservedVsSimulated on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        write(event, observedTravelTimes)
      }
    }
  }

  private def write(event: IterationEndsEvent, observedTravelTimes: Map[PathCache, Float]): Unit = {
    val filePathObservedVsSimulated = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      "tazODTravelTimeObservedVsSimulated.csv.gz"
    )
    val writerObservedVsSimulated = IOUtils.getBufferedWriter(filePathObservedVsSimulated)
    writerObservedVsSimulated.write("fromTAZId,toTAZId,hour,timeSimulated,timeObserved,counts")
    writerObservedVsSimulated.write("\n")

    var series = new mutable.ListBuffer[(Int, Double, Double)]()
    val categoryDataset = new HistogramDataset()
    var deltasOfObservedSimulatedTimes = new mutable.ListBuffer[Double]

    tazTreeMap.getTAZs
      .foreach { origin =>
        tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val key = PathCache(origin.tazId, destination.tazId, timeBin)
                observedTravelTimes.get(key).foreach { timeObserved =>
                  skimmer
                    .getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
                    .map(_.toSkimExternal)
                    .foreach { theSkim =>
                      series += ((theSkim.count, theSkim.time, timeObserved))
                      for (count <- 1 to theSkim.count)
                        deltasOfObservedSimulatedTimes += theSkim.time - timeObserved
                      writerObservedVsSimulated.write(
                        s"${origin.tazId},${destination.tazId},${timeBin},${theSkim.time},${timeObserved},${theSkim.count}\n"
                      )
                    }
                }
              }
          }
        }
      }

    categoryDataset.addSeries("Simulated-Observed", deltasOfObservedSimulatedTimes.toArray, histogramBinSize)

    writerObservedVsSimulated.close()

    val chartPath =
      event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, chartName)
    generateChart(series, chartPath)

    val histogramPath =
      event.getServices.getControlerIO.getIterationFilename(event.getServices.getIterationNumber, histogramName)
    generateHistogram(categoryDataset, histogramPath)
  }
}

object TravelTimeObserved extends LazyLogging {
  val chartName: String = "scatterplot_simulation_vs_reference.png"
  val histogramName: String = "simulation_vs_reference_histogram.png"
  val histogramBinSize: Int = 200

  val MaxDistanceFromBeamTaz: Double = 500.0 // 500 meters

  case class PathCache(from: Id[TAZ], to: Id[TAZ], hod: Int)

  def buildTAZ2MovementId(filePath: String, geo: GeoUtils, tazTreeMap: TAZTreeMap): Map[TAZ, Int] = {
    ProfilingUtils.timed(s"buildTAZ2MovementId from '$filePath'", x => logger.info(x)) {
      val mapper: Feature => (TAZ, Int, Double) = (feature: Feature) => {
        val centroid = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry].getCentroid
        val wgsCoord = new Coord(centroid.getX, centroid.getY)
        val utmCoord = geo.wgs2Utm(wgsCoord)
        val movId = feature.getProperty("MOVEMENT_ID").getValue.toString.toInt
        val taz: TAZ = tazTreeMap.getTAZ(utmCoord.getX, utmCoord.getY)
        val distance = geo.distUTMInMeters(utmCoord, taz.coord)
        (taz, movId, distance)
      }
      val xs: Array[(TAZ, Int, Double)] = GeoJsonReader.read(filePath, mapper)
      val filterByMaxDistance = xs.filter { case (taz, movId, distance) => distance <= MaxDistanceFromBeamTaz }
      val tazId2MovIdByMinDistance = filterByMaxDistance
        .groupBy { case (taz, _, _) => taz }
        .map {
          case (taz, arr) =>
            val (_, movId, _) = arr.minBy { case (_, _, distance) => distance }
            (taz, movId)
        }
      val numOfUniqueMovId = tazId2MovIdByMinDistance.values.toSet.size
      logger.info(
        s"xs size is ${xs.length}. tazId2MovIdByMinDistance size is ${tazId2MovIdByMinDistance.keys.size}. numOfUniqueMovId: $numOfUniqueMovId"
      )
      tazId2MovIdByMinDistance
    }
  }

  def buildPathCache2TravelTime(pathToAggregateFile: String, movId2Taz: Map[Int, TAZ]): Map[PathCache, Float] = {
    val observedTravelTimes: mutable.HashMap[PathCache, Float] = scala.collection.mutable.HashMap.empty
    ProfilingUtils.timed(s"buildPathCache2TravelTime from '$pathToAggregateFile'", x => logger.info(x)) {
      val mapReader: ICsvMapReader =
        new CsvMapReader(FileUtils.readerFromFile(pathToAggregateFile), CsvPreference.STANDARD_PREFERENCE)
      try {
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          val sourceid = line.get("sourceid").toInt
          val dstid = line.get("dstid").toInt
          val mean_travel_time = line.get("mean_travel_time").toFloat
          val hod = line.get("hod").toInt

          if (movId2Taz.contains(sourceid) && movId2Taz.contains(dstid)) {
            observedTravelTimes.put(PathCache(movId2Taz(sourceid).tazId, movId2Taz(dstid).tazId, hod), mean_travel_time)
          }

          line = mapReader.read(header: _*)
        }
      } finally {
        if (null != mapReader)
          mapReader.close()
      }
    }
    logger.info(s"observedTravelTimesOpt size is ${observedTravelTimes.keys.size}")
    observedTravelTimes.toMap
  }

  def generateHistogram(dataset: HistogramDataset, path: String): Unit = {
    dataset.setType(HistogramType.FREQUENCY)
    val chart = ChartFactory.createHistogram(
      "Simulated-Observed Frequency",
      "Simulated-Observed",
      "Frequency",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      false,
      false
    )
    GraphUtils.saveJFreeChartAsPNG(
      chart,
      path,
      GraphsStatsAgentSimEventsListener.GRAPH_WIDTH,
      GraphsStatsAgentSimEventsListener.GRAPH_HEIGHT
    )
  }

  def generateChart(series: mutable.ListBuffer[(Int, Double, Double)], path: String): Unit = {
    def drawLineHelper(color: Color, percent: Int, xyplot: XYPlot, max: Double, text: Double) = {
      xyplot.addAnnotation(
        new XYLineAnnotation(
          0,
          0,
          max * 2 * Math.cos(Math.toRadians(45 + percent)),
          max * 2 * Math.sin(Math.toRadians(45 + percent)),
          new BasicStroke(1f),
          color
        )
      )

      xyplot.addAnnotation(
        new XYTextAnnotation(
          s"$text%",
          max * Math.cos(Math.toRadians(45 + percent)) / 2,
          max * Math.sin(Math.toRadians(45 + percent)) / 2
        )
      )
    }

    val maxSkimCount = series.map(_._1).max
    val bucketsNum = Math.min(maxSkimCount, 4)
    val buckets = (1 to bucketsNum).map(_ * maxSkimCount / bucketsNum)
    def getClosest(num: Double) = buckets.minBy(v => math.abs(v - num))

    var dataset = new XYSeriesCollection()
    val seriesPerCount = mutable.HashMap[Int, XYSeries]()
    series.foreach {
      case (count, simulatedTime, observedTime) =>
        val closestBucket = getClosest(count)

        if (!seriesPerCount.contains(closestBucket))
          seriesPerCount(closestBucket) = new XYSeries(closestBucket.toString, false)

        seriesPerCount(closestBucket).add(simulatedTime, observedTime)
    }
    seriesPerCount.toSeq.sortBy(_._1).foreach {
      case (_, seriesToAdd) =>
        dataset.addSeries(seriesToAdd)
    }

    val chart = ChartFactory.createScatterPlot(
      "TAZ TravelTimes Observed Vs. Simulated",
      "Simulated",
      "Observed",
      dataset,
      PlotOrientation.VERTICAL,
      true,
      true,
      false
    )

    val xyplot = chart.getPlot.asInstanceOf[XYPlot]
    xyplot.setDomainCrosshairVisible(false)
    xyplot.setRangeCrosshairVisible(false)

    val colors = List(
      new Color(125, 125, 250), // light blue
      new Color(32, 32, 253), // dark blue
      new Color(255, 87, 126), // light red
      new Color(255, 0, 60) // dark red
    )

    (0 until seriesPerCount.size).map { counter =>
      val renderer = xyplot
        .getRendererForDataset(xyplot.getDataset(0))

      renderer.setSeriesShape(counter, new Ellipse2D.Double(0, 0, 5, 5))
      renderer.setSeriesPaint(counter, colors(counter % colors.length))
    }

    val max = Math.max(
      dataset.getDomainLowerBound(false),
      dataset.getRangeUpperBound(false)
    )

    if (max > 0) {
      xyplot.getDomainAxis.setRange(0.0, max)
      xyplot.getRangeAxis.setRange(0.0, max)
    }

    xyplot.getDomainAxis.setAutoRange(false)
    xyplot.getRangeAxis.setAutoRange(false)

    xyplot.getDomainAxis.setTickLabelInsets(new RectangleInsets(10.0, 10.0, 10.0, 10.0))
    xyplot.getRangeAxis.setTickLabelInsets(new RectangleInsets(10.0, 10.0, 10.0, 10.0))

    // diagonal line
    chart.getXYPlot.addAnnotation(
      new XYLineAnnotation(
        0,
        0,
        xyplot.getDomainAxis.getRange.getUpperBound,
        xyplot.getRangeAxis.getRange.getUpperBound
      )
    )

    val percents = List(
      (18, Color.RED, -50.0),
      (-18, Color.RED, 100.0),
      (36, Color.BLUE, -83.0),
      (-36, Color.BLUE, 500.0)
    )

    percents.foreach {
      case (percent: Int, color: Color, value: Double) =>
        drawLineHelper(
          color,
          percent,
          xyplot,
          max,
          value
        )
    }

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      path,
      1000,
      1000
    )
  }
}
