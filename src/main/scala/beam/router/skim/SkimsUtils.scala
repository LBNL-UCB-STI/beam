package beam.router.skim

import java.awt.geom.Ellipse2D
import java.awt.{BasicStroke, Color}

import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.analysis.plots.{GraphUtils, GraphsStatsAgentSimEventsListener}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{
  BIKE,
  CAR,
  CAV,
  DRIVE_TRANSIT,
  RIDE_HAIL,
  RIDE_HAIL_POOLED,
  RIDE_HAIL_TRANSIT,
  TRANSIT,
  WALK,
  WALK_TRANSIT
}
import beam.sim.{BeamScenario, BeamServices}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.{FileUtils, GeoJsonReader, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Geometry
import org.jfree.chart.ChartFactory
import org.jfree.chart.annotations.{XYLineAnnotation, XYTextAnnotation}
import org.jfree.chart.plot.{PlotOrientation, XYPlot}
import org.jfree.data.statistics.{HistogramDataset, HistogramType}
import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import org.jfree.ui.RectangleInsets
import org.matsim.api.core.v01.{Coord, Id}
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable

object SkimsUtils extends LazyLogging {

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  val transitSpeedMeterPerSec: Double = 5.409184
  val bicycleSpeedMeterPerSec: Double = 3
  // 3.1 mph -> 1.38 meter per second
  val walkSpeedMeterPerSec: Double = 1.38
  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  val trafficSignalSpacing: Double = 402.336
  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  val waitingTimeAtAnIntersection: Double = 17.25

  val speedMeterPerSec: Map[BeamMode, Double] = Map(
    CAV               -> carSpeedMeterPerSec,
    CAR               -> carSpeedMeterPerSec,
    WALK              -> walkSpeedMeterPerSec,
    BIKE              -> bicycleSpeedMeterPerSec,
    WALK_TRANSIT      -> transitSpeedMeterPerSec,
    DRIVE_TRANSIT     -> transitSpeedMeterPerSec,
    RIDE_HAIL         -> carSpeedMeterPerSec,
    RIDE_HAIL_POOLED  -> carSpeedMeterPerSec,
    RIDE_HAIL_TRANSIT -> transitSpeedMeterPerSec,
    TRANSIT           -> transitSpeedMeterPerSec
  )

  case class PathCache(from: Id[TAZ], to: Id[TAZ], hod: Int)

  def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def timeToBin(departTime: Int, timeWindow: Int): Int = departTime / timeWindow

  def distanceAndTime(mode: BeamMode, originUTM: Location, destinationUTM: Location): (Int, Int) = {
    val speed = mode match {
      case CAR | CAV | RIDE_HAIL                                      => carSpeedMeterPerSec
      case RIDE_HAIL_POOLED                                           => carSpeedMeterPerSec / 1.1
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => transitSpeedMeterPerSec
      case BIKE                                                       => bicycleSpeedMeterPerSec
      case _                                                          => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(originUTM, destinationUTM)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
    (travelDistance, travelTime)
  }

  def getRideHailCost(
    mode: BeamMode,
    distanceInMeters: Double,
    timeInSeconds: Double,
    beamConfig: BeamConfig
  ): Double = {
    mode match {
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost
      case _ =>
        0.0
    }
  }

  def buildPathCache2TravelTime(
    pathToAggregateFile: String,
    movId2Taz: Map[Int, TAZ]
  ): Map[PathCache, Float] = {
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

  def buildTAZ2MovementId(
    filePath: String,
    geo: GeoUtils,
    tazTreeMap: TAZTreeMap,
    maxDistanceFromBeamTaz: Double
  ): Map[TAZ, Int] = {
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
      val filterByMaxDistance = xs.filter { case (_, _, distance) => distance <= maxDistanceFromBeamTaz }
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

  def buildObservedODTravelTime(
    beamConfig: BeamConfig,
    geo: GeoUtils,
    beamScenario: BeamScenario,
    maxDistanceFromBeamTaz: Double
  ): Map[PathCache, Float] = {
    val zoneBoundariesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath
    val zoneODTravelTimesFilePath = beamConfig.beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath
    if (zoneBoundariesFilePath.nonEmpty && zoneODTravelTimesFilePath.nonEmpty) {
      val tazToMovId: Map[TAZ, Int] = buildTAZ2MovementId(
        zoneBoundariesFilePath,
        geo,
        beamScenario.tazTreeMap,
        maxDistanceFromBeamTaz
      )
      val movId2Taz: Map[Int, TAZ] = tazToMovId.map { case (k, v) => v -> k }
      buildPathCache2TravelTime(zoneODTravelTimesFilePath, movId2Taz)
    } else
      Map.empty[PathCache, Float]
  }

  def generateChart(series: mutable.ListBuffer[(Int, Double, Double)], path: String): Unit = {
    def drawLineHelper(color: Color, percent: Int, xyplot: XYPlot, max: Double, text: Double): Unit = {
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

    (0 until seriesPerCount.size).foreach { counter =>
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

}
