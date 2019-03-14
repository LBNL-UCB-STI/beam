package beam.router

import java.io.{BufferedInputStream, FileInputStream, FileReader, InputStreamReader, Reader}
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

import scala.collection.concurrent.TrieMap

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer._
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
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.{BeamServices, BeamWarmStart}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import com.google.inject.Inject
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

//TODO to be validated against google api
class BeamSkimmer @Inject()(
  beamConfig: BeamConfig
) extends IterationEndsListener {

  private val SKIMS_FILE_NAME = "skims.csv.gz"

  // The OD/Mode/Time Matrix
  private var previousSkims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = initialPreviousSkims()
  private var skims: TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = TrieMap()
  private val modalAverage: TrieMap[BeamMode, SkimInternal] = TrieMap()

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

  def getTimeDistanceAndCost(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServicesOpt: Option[BeamServices] = None
  ): Skim = {
    beamServicesOpt match {
      case Some(beamServices) =>
        val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
        val destTaz = beamServices.tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
        getSkimValue(departureTime, mode, origTaz, destTaz) match {
          case Some(skimValue) =>
            skimValue.toSkimExternal
          case None =>
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
            Skim(travelTime, travelDistance, travelCost, 0)
        }
      case None =>
        val (travelDistance, travelTime) = distanceAndTime(mode, origin, destination)
        Skim(travelTime, travelDistance, 0.0, 0)
    }
  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServices: BeamServices
  ): (Double, Double) = {
    val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = beamServices.tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        modalAverage.get(RIDE_HAIL) match {
          case Some(skim) =>
            skim
          case None =>
            SkimInternal(1.0, 0, 1.0, 0)
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
            SkimInternal(1.1, 0, beamConfig.beam.agentsim.agents.rideHail.pooledToRegularRideCostRatio, 0)
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

  def observeTrip(trip: EmbodiedBeamTrip, beamServices: BeamServices): Option[SkimInternal] = {
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
        trip.beamLegs().map(_.travelPath.distanceInM).sum,
        trip.costEstimate,
        1
      )
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = SkimInternal(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
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
    val fileHeader = "hour,mode,origTaz,destTaz,travelTimeInS,cost,distanceInM,numObservations"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.outputFileBaseName + ".csv.gz"
    )
    FileUtils.writeToFile(
      filePath,
      Some(fileHeader),
      skims
        .map { keyVal =>
          s"${keyVal._1._1},${keyVal._1._2},${keyVal._1._3},${keyVal._1._4},${keyVal._2.time},${keyVal._2.cost},${keyVal._2.distance},${keyVal._2.count}"
        }
        .mkString("\n"),
      None
    )
    previousSkims = skims
    skims = new TrieMap()
  }
}

object BeamSkimmer {
  val outputFileBaseName = "skims"

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

  case class SkimInternal(time: Double, distance: Double, cost: Double, count: Int) {
    def toSkimExternal: Skim = Skim(time.toInt, distance, cost, count)
  }

  case class Skim(time: Int, distance: Double, cost: Double, count: Int)

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
        val distanceInMeters = line.get("distanceInM")
        val numObservations = line.get("numObservations")

        val key = (
          hour.toInt,
          BeamMode.fromString(mode.toLowerCase()).get,
          Id.create(origTazId, classOf[TAZ]),
          Id.create(destTazId, classOf[TAZ]),
        )
        val value = SkimInternal(hour.toDouble, distanceInMeters.toDouble, cost.toDouble, numObservations.toInt)
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
