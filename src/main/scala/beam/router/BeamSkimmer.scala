package beam.router

import java.awt.Color
import java.io.{File, FileInputStream, _}
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.analysis.plots.GraphUtils
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, BeamWarmStart}
import com.google.inject.Inject
import com.vividsolutions.jts.geom.Geometry
import org.geotools.feature.FeatureIterator
import org.geotools.geojson.feature.FeatureJSON
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.{PlotOrientation, XYPlot}
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.xy.{XYSeries, XYSeriesCollection}
import org.jfree.util.ShapeUtilities
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.utils.io.IOUtils
import org.opengis.feature.Feature
import org.opengis.feature.simple.SimpleFeature
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.language.implicitConversions

//TODO to be validated against google api
class BeamSkimmer @Inject()(val beamConfig: BeamConfig) extends IterationEndsListener {

  private val SKIMS_FILE_NAME = "skims.csv.gz"

  private var beamServicesOpt: Option[BeamServices] = None

  // The OD/Mode/Time Matrix
  private var previousSkims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = initialPreviousSkims()
  private var skims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = TrieMap()
  private val modalAverage: TrieMap[BeamMode, SkimInternal] = TrieMap()

  case class PathCache(from: Id[TAZ], to: Id[TAZ], hod: Int)

  private implicit def simpleFeatureIterator[F <: Feature](it: FeatureIterator[F]) = new Iterator[F] {
    override def hasNext: Boolean = if (!it.hasNext) {
      it.close()
      false
    } else true

    override def next(): F = it.next()
  }

  private def skimsFilePath: Option[String] = {
    val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt
    BeamWarmStart(beamConfig, maxHour).getWarmStartFilePath(SKIMS_FILE_NAME)
  }

  private def initialPreviousSkims(): TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = {
    if (beamConfig.beam.warmStart.enabled) {
      skimsFilePath
        .map(BeamSkimmer.readCsvFile)
        .getOrElse(TrieMap.empty)
    } else {
      TrieMap.empty
    }
  }

  def setBeamServices(newBeamServices: BeamServices) = {
    beamServicesOpt = Some(newBeamServices)
  }

  def getSkimDefaultValue(
    mode: BeamMode,
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: Id[BeamVehicleType],
    beamServices: BeamServices
  ): Skim = {
    val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
    val travelCost: Double = mode match {
      case CAR | CAV =>
        DrivingCost.estimateDrivingCost(
          new BeamLeg(
            departureTime,
            mode,
            travelTime,
            new BeamPath(null, null, None, null, null, travelDistance)
          ),
          vehicleTypeId,
          beamServices
        )
      case RIDE_HAIL =>
        beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
      case RIDE_HAIL_POOLED =>
        0.6 * (beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamServices.beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0)
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => 0.25 * travelDistance / 1609
      case _                                                          => 0.0
    }
    Skim(
      travelTime,
      travelTime,
      travelCost + travelTime * beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime / 3600,
      travelDistance,
      travelCost,
      0
    )
  }

  def getTimeDistanceAndCost(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType]
  ): Skim = {
    beamServicesOpt match {
      case Some(beamServices) =>
        val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        val destTaz = beamServices.tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
        getSkimValue(departureTime, mode, origTaz, destTaz) match {
          case Some(skimValue) =>
            skimValue.toSkimExternal
          case None =>
            getSkimDefaultValue(mode, origin, destination, departureTime, vehicleTypeId, beamServices)
        }
      case None =>
        val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
        Skim(
          travelTime,
          travelTime,
          travelTime * beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime / 3600,
          travelDistance,
          0.0,
          0
        )
    }
  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType]
  ): (Double, Double) = {
    val origTaz = beamServicesOpt.get.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = beamServicesOpt.get.tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        modalAverage.get(RIDE_HAIL) match {
          case Some(skim) =>
            skim
          case None =>
            SkimInternal(1.0, 1.0, 1.0, 0, 1.0, 0)
        }
    }
    val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        modalAverage.get(RIDE_HAIL_POOLED) match {
          case Some(skim) =>
            skim
          case None =>
            SkimInternal(
              1.1,
              1.1,
              beamServicesOpt.get.beamConfig.beam.agentsim.agents.rideHail.pooledToRegularRideCostRatio * 1.1,
              0,
              beamServicesOpt.get.beamConfig.beam.agentsim.agents.rideHail.pooledToRegularRideCostRatio,
              0
            )
        }
    }
    (pooled.time / solo.time, pooled.cost / solo.cost)
  }

  private def distanceAndTime(mode: BeamMode, origin: Location, destination: Location) = {
    val speed = mode match {
      case CAR | CAV | RIDE_HAIL                                      => carSpeedMeterPerSec
      case RIDE_HAIL_POOLED                                           => carSpeedMeterPerSec * 1.1
      case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT | RIDE_HAIL_TRANSIT => transitSpeedMeterPerSec
      case BIKE                                                       => bicycleSpeedMeterPerSec
      case _                                                          => walkSpeedMeterPerSec
    }
    val travelDistance: Int = Math.ceil(GeoUtils.minkowskiDistFormula(origin, destination)).toInt
    val travelTime: Int = Math
      .ceil(travelDistance / speed)
      .toInt + ((travelDistance / trafficSignalSpacing).toInt * waitingTimeAtAnIntersection).toInt
    (travelDistance, travelTime)
  }

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[SkimInternal] = {
    skims.get((timeToBin(time), mode, orig, dest)) match {
      case someSkim @ Some(_) =>
        someSkim
      case None =>
        previousSkims.get((timeToBin(time), mode, orig, dest))
    }
  }

  def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    beamServices: BeamServices
  ): Option[SkimInternal] = {
    val mode = trip.tripClassifier
    val correctedTrip = mode match {
      case WALK =>
        trip.beamLegs()
      case _ =>
        trip.beamLegs().drop(1).dropRight(1)
    }
    val origLeg = correctedTrip.head
    val origCoord = beamServices.geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamServices.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = correctedTrip.last
    val destCoord = beamServices.geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamServices.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = timeToBin(origLeg.startTime)
    val key = (timeBin, mode, origTaz, destTaz)
    val payload =
      SkimInternal(
        trip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInHours * 3600,
        generalizedCost,
        trip.beamLegs().map(_.travelPath.distanceInM).sum,
        trip.costEstimate,
        1
      )
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = SkimInternal(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          mergeAverage(existingSkim.generalizedTime, existingSkim.count, payload.generalizedTime),
          mergeAverage(existingSkim.generalizedCost, existingSkim.count, payload.generalizedCost),
          mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          existingSkim.count + 1
        )
        skims.put(key, newPayload)
      case None =>
        skims.put(key, payload)
    }
  }

  def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def mergeAverage(existingAverage: Double, existingCount: Int, newValue: Double): Double = {
    (existingAverage * existingCount + newValue) / (existingCount + 1)
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    writeObservedSkims(event)
    if (beamServicesOpt.isDefined) writeCarSkimsForPeakNonPeakPeriods(event)
    // Writing full skims are very large, but code is preserved here in case we want to enable it.
    // TODO make this a configurable output "writeFullSkimsInterval" with default of 0
    // if(beamServicesOpt.isDefined) writeFullSkims(event)

    if (beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileCensustracts.nonEmpty &&
      beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileAggregates.nonEmpty) {
      writeTravelTimeObservedVsSimulated(event)
    }

    previousSkims = skims
    skims = new TrieMap()
  }

  def averageAndWriteSkims(
    timePeriodString: String,
    hoursIncluded: List[Int],
    origin: TAZ,
    destination: TAZ,
    mode: BeamMode.CAR.type,
    get: BeamServices,
    dummyId: Id[BeamVehicleType],
    writer: BufferedWriter
  ) = {
    val individualSkims = hoursIncluded.map { timeBin =>
      getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
        .map(_.toSkimExternal)
        .getOrElse {
          val adjustedDestCoord = if (origin.equals(destination)) {
            new Coord(
              origin.coord.getX,
              origin.coord.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
            )
          } else {
            destination.coord
          }
          getSkimDefaultValue(
            mode,
            origin.coord,
            adjustedDestCoord,
            timeBin * 3600,
            dummyId,
            beamServicesOpt.get
          )
        }
    }
    val weights = individualSkims.map(sk => Math.max(sk.count, 1).toDouble)
    val sumWeights = weights.sum
    val weightedDistance = individualSkims.map(_.distance).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedTime = individualSkims.map(_.time).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedGeneralizedTime = individualSkims
      .map(_.generalizedTime)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights
    val weightedCost = individualSkims.map(_.cost).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights
    val weightedGeneralizedCost = individualSkims
      .map(_.generalizedCost)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights

    writer.write(
      s"$timePeriodString,$mode,${origin.tazId},${destination.tazId},${weightedTime},${weightedGeneralizedTime},${weightedCost},${weightedGeneralizedCost},${weightedDistance},${sumWeights}\n"
    )
  }

  def writeCarSkimsForPeakNonPeakPeriods(event: IterationEndsEvent) = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = List(CAR)
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.excerptSkimsFileBaseName + ".csv.gz"
    )
    val dummyId = Id.create("NA", classOf[BeamVehicleType])
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    beamServicesOpt.get.tazTreeMap.getTAZs
      .foreach { origin =>
        beamServicesOpt.get.tazTreeMap.getTAZs.foreach { destination =>
          modes.foreach { mode =>
            averageAndWriteSkims(
              "AM",
              morningPeakHours,
              origin,
              destination,
              mode,
              beamServicesOpt.get,
              dummyId,
              writer
            )
            averageAndWriteSkims(
              "PM",
              afternoonPeakHours,
              origin,
              destination,
              mode,
              beamServicesOpt.get,
              dummyId,
              writer
            )
            averageAndWriteSkims(
              "OffPeak",
              nonPeakHours,
              origin,
              destination,
              mode,
              beamServicesOpt.get,
              dummyId,
              writer
            )
          }
        }
      }

    writer.close()
  }

  def writeTravelTimeObservedVsSimulated(event: IterationEndsEvent): Unit = {
    val uniqueModes = List(CAR)
    val uniqueTimeBins = (0 to 23)

    val dummyId = Id.create("NA", classOf[BeamVehicleType])

    val filePathObservedVsSimulated = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      "tazODTravelTimeObservedVsSimulated.csv.gz"
    )
    val writerObservedVsSimulated = IOUtils.getBufferedWriter(filePathObservedVsSimulated)
    writerObservedVsSimulated.write("fromTAZId,toTAZId,hour,timeSimulated,timeObserved")
    writerObservedVsSimulated.write("\n")

    val fjson = new FeatureJSON
    val file = new File(beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileCensustracts)
    val fileIn = new FileInputStream(file)
    val features = fjson.readFeatureCollection(fileIn).features()

    val observedTravelTimes: mutable.HashMap[PathCache, Float] = scala.collection.mutable.HashMap.empty
    val series: XYSeries = new XYSeries("Time")

    val CensusTractHash: Map[Int, Id[TAZ]] = features.map(feature =>
    {
      val centroid = feature.asInstanceOf[SimpleFeature].getDefaultGeometry.asInstanceOf[Geometry].getCentroid
      val coord = beamServicesOpt.get.geo.wgs2Utm(new Coord(centroid.getX, centroid.getY))
      (feature.getProperty("MOVEMENT_ID").getValue.toString.toInt, beamServicesOpt.get.tazTreeMap.getTAZ(coord.getX, coord.getY).tazId)
    }).toMap

    var mapReader: ICsvMapReader = null
    try {
      val reader = buildReader(beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileAggregates)
      mapReader = new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val sourceid = line.get("sourceid").toInt
        val dstid = line.get("dstid").toInt
        val mean_travel_time = line.get("mean_travel_time").toFloat
        val hod = line.get("hod").toInt

        if (CensusTractHash.contains(sourceid) && CensusTractHash.contains(dstid)) {
          observedTravelTimes.put(PathCache(CensusTractHash(sourceid), CensusTractHash(dstid), hod), mean_travel_time)
        }

        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }

    beamServicesOpt.get.tazTreeMap.getTAZs
      .foreach { origin =>
        beamServicesOpt.get.tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim = getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
                  .map(_.toSkimExternal)
                  .getOrElse {
                    if (origin.equals(destination)) {
                      val newDestCoord = new Coord(
                        origin.coord.getX,
                        origin.coord.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
                      )
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        newDestCoord,
                        timeBin * 3600,
                        dummyId,
                        beamServicesOpt.get
                      )
                    } else {
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        destination.coord,
                        timeBin * 3600,
                        dummyId,
                        beamServicesOpt.get
                      )
                    }
                  }

                val key = PathCache(origin.tazId, destination.tazId, timeBin)
                observedTravelTimes.get(key).foreach { timeObserved =>
                  if (beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileChart.nonEmpty) {
                    series.add(theSkim.time, timeObserved)
                  }
                  writerObservedVsSimulated.write(
                    s"${origin.tazId},${destination.tazId},${timeBin},${theSkim.time},${timeObserved)}\n"
                  )
                }
              }
          }
        }
      }
    writerObservedVsSimulated.close()

    if (beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileChart.nonEmpty) {
      generateChart(event, series)
    }
  }

  def generateChart(event: IterationEndsEvent, series: XYSeries): Unit = {
    val dataset = new XYSeriesCollection
    dataset.addSeries(series)

    val chart = ChartFactory.createScatterPlot(
      "TAZ TravelTimes Observed Vs. Simulated",
      "Simulated", "Observed",
      dataset,
      PlotOrientation.VERTICAL,
      false, true, false);

    val xyplot = chart.getPlot.asInstanceOf[XYPlot]

    val renderer = new XYLineAndShapeRenderer
    renderer.setSeriesShape(0, ShapeUtilities.createDiamond(1))
    renderer.setSeriesPaint(0, Color.RED)
    renderer.setSeriesLinesVisible(0, false)

    xyplot.setRenderer(0, renderer)

    GraphUtils.saveJFreeChartAsPNG(
      chart,
      event.getServices.getControlerIO.getIterationFilename(
        event.getIteration,
        beamConfig.beam.calibration.roadNetwork.travelTimes.benchmarkFileChart
      ),
      1000,
      1000
    )
  }

  def writeFullSkims(event: IterationEndsEvent) = {
    val fileHeader =
      "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.fullSkimsFileBaseName + ".csv.gz"
    )
    val uniqueModes = skims.map(keyVal => keyVal._1._2).toList.distinct
    val uniqueTimeBins = (0 to 23)

    val dummyId = Id.create("NA", classOf[BeamVehicleType])

    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    beamServicesOpt.get.tazTreeMap.getTAZs
      .foreach { origin =>
        beamServicesOpt.get.tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim = getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
                  .map(_.toSkimExternal)
                  .getOrElse {
                    if (origin.equals(destination)) {
                      val newDestCoord = new Coord(
                        origin.coord.getX,
                        origin.coord.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
                      )
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        newDestCoord,
                        timeBin * 3600,
                        dummyId,
                        beamServicesOpt.get
                      )
                    } else {
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        destination.coord,
                        timeBin * 3600,
                        dummyId,
                        beamServicesOpt.get
                      )
                    }
                  }

                writer.write(
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count}\n"
                )
              }
          }
        }
      }
    writer.close()
  }

  def writeObservedSkims(event: IterationEndsEvent) = {
    val fileHeader =
      "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.observedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    skims.foreach { keyVal =>

      writer.write(
        s"${keyVal._1._1},${keyVal._1._2},${keyVal._1._3},${keyVal._1._4},${keyVal._2.time},${keyVal._2.generalizedTime},${keyVal._2.cost},${keyVal._2.generalizedCost},${keyVal._2.distance},${keyVal._2.count}\n"
      )
    }
    writer.close
  }
}

object BeamSkimmer {
  val observedSkimsFileBaseName = "skims"
  val fullSkimsFileBaseName = "skimsFull"
  val excerptSkimsFileBaseName = "skimsExcerpt"

  // 22.2 mph (9.924288 meter per second), is the average speed in cities
  //TODO better estimate can be drawn from city size
  // source: https://www.mitpressjournals.org/doi/abs/10.1162/rest_a_00744
  private val carSpeedMeterPerSec: Double = 9.924288
  // 12.1 mph (5.409184 meter per second), is average bus speed
  // source: https://www.apta.com/resources/statistics/Documents/FactBook/2017-APTA-Fact-Book.pdf
  // assuming for now that it includes the headway
  private val transitSpeedMeterPerSec: Double = 5.409184
  private val bicycleSpeedMeterPerSec: Double = 3
  // 3.1 mph -> 1.38 meter per second
  private val walkSpeedMeterPerSec: Double = 1.38
  // 940.6 Traffic Signal Spacing, Minor is 1,320 ft => 402.336 meters
  private val trafficSignalSpacing: Double = 402.336
  // average waiting time at an intersection is 17.25 seconds
  // source: https://pumas.nasa.gov/files/01_06_00_1.pdf
  private val waitingTimeAtAnIntersection: Double = 17.25

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

  case class SkimInternal(
    time: Double,
    generalizedTime: Double,
    generalizedCost: Double,
    distance: Double,
    cost: Double,
    count: Int
  ) {
    //NOTE: All times in seconds here
    def toSkimExternal: Skim = Skim(time.toInt, generalizedTime, generalizedCost, distance, cost, count)
  }

  case class Skim(
    time: Int,
    generalizedTime: Double,
    generalizedCost: Double,
    distance: Double,
    cost: Double,
    count: Int
  )

  private def readCsvFile(filePath: String): TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = {
    var mapReader: ICsvMapReader = null
    val res = TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal]()
    try {
      val reader = buildReader(filePath)
      mapReader = new CsvMapReader(reader, CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val hour = line.get("hour")
        val mode = line.get("mode")
        val origTazId = line.get("origTaz")
        val destTazId = line.get("destTaz")
        val cost = line.get("cost")
        val time = line.get("travelTimeInS")
        val generalizedTime = line.get("generalizedTimeInS")
        val generalizedCost = line.get("generalizedCost")
        val distanceInMeters = line.get("distanceInM")
        val numObservations = line.get("numObservations")

        val key = (
          hour.toInt,
          BeamMode.fromString(mode.toLowerCase()).get,
          Id.create(origTazId, classOf[TAZ]),
          Id.create(destTazId, classOf[TAZ]),
        )
        val value =
          SkimInternal(
            time.toDouble,
            generalizedTime.toDouble,
            generalizedCost.toDouble,
            distanceInMeters.toDouble,
            cost.toDouble,
            numObservations.toInt
          )
        res.put(key, value)
        line = mapReader.read(header: _*)
      }

    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res
  }

  private def buildReader(filePath: String): Reader = {
    if (filePath.endsWith(".gz")) {
      new InputStreamReader(
        new GZIPInputStream(new BufferedInputStream(new FileInputStream(filePath)))
      )
    } else {
      new FileReader(filePath)
    }
  }

}
