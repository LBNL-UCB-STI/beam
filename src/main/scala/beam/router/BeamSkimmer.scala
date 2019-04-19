package beam.router

import java.io._
import java.util.concurrent.TimeUnit

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer.Label
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
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.vehiclesharing.VehicleManager
import beam.sim.{BeamServices, BeamWarmStart}
import beam.utils.{FileUtils, ProfilingUtils}
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.index.quadtree.Quadtree
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.groups.TravelTimeCalculatorConfigGroup
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.language.implicitConversions
import scala.util.control.NonFatal

//TODO to be validated against google api
class BeamSkimmer @Inject()(val beamConfig: BeamConfig, val beamServices: BeamServices) extends LazyLogging {
  import BeamSkimmer._

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
      try {
        skimsFilePath
          .map(BeamSkimmer.readCsvFile)
          .getOrElse(TrieMap.empty)
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load previous skim from '${skimsFilePath}': ${ex.getMessage}", ex)
          TrieMap.empty
      }
    } else {
      TrieMap.empty
    }
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
      0,
      0.0 // TODO get default energy information
    )
  }

  def getTimeDistanceAndCost(
    origin: Location,
    destination: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType]
  ): Skim = {
    val origTaz = beamServices.tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = beamServices.tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    getSkimValue(departureTime, mode, origTaz, destTaz) match {
      case Some(skimValue) =>
        skimValue.toSkimExternal
      case None =>
        getSkimDefaultValue(mode, origin, destination, departureTime, vehicleTypeId, beamServices)
    }
  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType]
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
            SkimInternal(1.0, 1.0, 1.0, 0, 1.0, 0, 1.0)
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
              beamServices.beamConfig.beam.agentsim.agents.rideHail.pooledToRegularRideCostRatio * 1.1,
              0,
              beamServices.beamConfig.beam.agentsim.agents.rideHail.pooledToRegularRideCostRatio,
              0,
              1.1
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

  def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[SkimInternal] = {
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
    energyConsumption: Double,
    beamServices: BeamServices
  ): Option[SkimInternal] = {
    val mode = trip.tripClassifier
    val correctedTrip = mode match {
      case WALK =>
        trip
      case _ =>
        val legs = trip.legs.drop(1).dropRight(1)
        EmbodiedBeamTrip(legs)
    }
    val beamLegs = correctedTrip.beamLegs()
    val origLeg = beamLegs.head
    val origCoord = beamServices.geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamServices.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = beamLegs.last
    val destCoord = beamServices.geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamServices.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = timeToBin(origLeg.startTime)
    val key = (timeBin, mode, origTaz, destTaz)
    val payload =
      SkimInternal(
        correctedTrip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInHours * 3600,
        generalizedCost,
        beamLegs.map(_.travelPath.distanceInM).sum,
        correctedTrip.costEstimate,
        1,
        energyConsumption
      )
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = SkimInternal(
          mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          mergeAverage(existingSkim.generalizedTime, existingSkim.count, payload.generalizedTime),
          mergeAverage(existingSkim.generalizedCost, existingSkim.count, payload.generalizedCost),
          mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          existingSkim.count + 1,
          mergeAverage(existingSkim.energy, existingSkim.count, payload.energy)
        )
        skims.put(key, newPayload)
      case None =>
        skims.put(key, payload)
    }
  }

  def timeToBin(departTime: Int, timeWindow: Double = 3600.0): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / timeWindow).toInt, (24 * 3600 / timeWindow).toInt)
  }

  def mergeAverage(existingAverage: Double, existingCount: Int, newValue: Double): Double = {
    (existingAverage * existingCount + newValue) / (existingCount + 1)
  }

  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    ProfilingUtils.timed(s"writeObservedSkims on iteration ${event.getIteration}", x => logger.info(x)) {
      writeObservedSkims(event)
    }
    ProfilingUtils.timed(s"writeCarSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}", x => logger.info(x)) {
      writeCarSkimsForPeakNonPeakPeriods(event)
    }
    ProfilingUtils.timed(s"writeObservedSkimsPlus on iteration ${event.getIteration}", x => logger.info(x)) {
      writeObservedSkimsPlus(event)
    }
    // Writing full skims are very large, but code is preserved here in case we want to enable it.
    // TODO make this a configurable output "writeFullSkimsInterval" with default of 0
    // if(beamServicesOpt.isDefined) writeFullSkims(event)

    previousSkims = skims
    skims = new TrieMap()
    previousSkimsPlus = skimsPlus
    skimsPlus = new TrieMap()
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
  ): Unit = {
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
            beamServices
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
    val weightedEnergy = individualSkims.map(_.energy).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights

    writer.write(
      s"$timePeriodString,$mode,${origin.tazId},${destination.tazId},${weightedTime},${weightedGeneralizedTime},${weightedCost},${weightedGeneralizedCost},${weightedDistance},${sumWeights},$weightedEnergy\n"
    )
  }

  def writeCarSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = List(CAR)
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.excerptSkimsFileBaseName + ".csv.gz"
    )
    val dummyId = Id.create("NA", classOf[BeamVehicleType])
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    beamServices.tazTreeMap.getTAZs
      .foreach { origin =>
        beamServices.tazTreeMap.getTAZs.foreach { destination =>
          modes.foreach { mode =>
            averageAndWriteSkims(
              "AM",
              morningPeakHours,
              origin,
              destination,
              mode,
              beamServices,
              dummyId,
              writer
            )
            averageAndWriteSkims(
              "PM",
              afternoonPeakHours,
              origin,
              destination,
              mode,
              beamServices,
              dummyId,
              writer
            )
            averageAndWriteSkims(
              "OffPeak",
              nonPeakHours,
              origin,
              destination,
              mode,
              beamServices,
              dummyId,
              writer
            )
          }
        }
      }

    writer.close()
  }

  def writeFullSkims(event: IterationEndsEvent): Unit = {
    val fileHeader =
      "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
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

    beamServices.tazTreeMap.getTAZs
      .foreach { origin =>
        beamServices.tazTreeMap.getTAZs.foreach { destination =>
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
                        beamServices
                      )
                    } else {
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        destination.coord,
                        timeBin * 3600,
                        dummyId,
                        beamServices
                      )
                    }
                  }

                writer.write(
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count},${theSkim.energy}\n"
                )
              }
          }
        }
      }
    writer.close()
  }

  def writeObservedSkims(event: IterationEndsEvent): Unit = {
    val fileHeader =
      "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.observedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    skims.foreach { keyVal =>
      writer.write(
        s"${keyVal._1._1},${keyVal._1._2},${keyVal._1._3},${keyVal._1._4},${keyVal._2.time},${keyVal._2.generalizedTime},${keyVal._2.cost},${keyVal._2.generalizedCost},${keyVal._2.distance},${keyVal._2.count},${keyVal._2.energy}\n"
      )
    }
    writer.close()
  }

  // **********
  // Skim Plus
  private var previousSkimsPlus: TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double] =
    initialPreviousSkimsPlus()
  private var skimsPlus: TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double] = TrieMap()
  private val timeWindow: Int = 5 * 60
  private def skimsPlusFilePath: Option[String] = {
    val maxHour = TimeUnit.SECONDS.toHours(new TravelTimeCalculatorConfigGroup().getMaxTime).toInt
    BeamWarmStart(beamConfig, maxHour).getWarmStartFilePath(observedSkimsPlusFileBaseName + ".csv.gz")
  }

  def getSkimPlusValue(time: Int, taz: Id[TAZ], vehiclesManager: Id[VehicleManager], label: Label): Option[Double] = {
    val timeBin = timeToBin(time, timeWindow)
    previousSkimsPlus.get((timeBin, taz, vehiclesManager, label))
  }

  def getSkimPlusValues(
    startTime: Int,
    endTime: Int,
    taz: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    label: Label
  ): Vector[Double] = {
    (timeToBin(startTime, timeWindow) to timeToBin(endTime, timeWindow))
      .map { i =>
        previousSkimsPlus.get((i, taz, vehicleManager, label))
      }
      .toVector
      .flatten
  }

  def observeVehicleDemandByTAZ(
    time: Int,
    location: Coord,
    vehicleManager: Id[VehicleManager],
    label: Label,
    who: Option[Id[Person]]
  ): Unit = {
    who match {
      case Some(_) =>
        val timeBin = timeToBin(time, timeWindow)
        val taz = beamServices.tazTreeMap.getTAZ(location.getX, location.getY)
        val key = (timeBin, taz.tazId, vehicleManager, label)
        skimsPlus.put(key, skimsPlus.getOrElse(key, 1.0) + 1.0)
      case _ =>
    }
  }

  def observeVehicleAvailabilityByTAZ(
    time: Int,
    taz: TAZ,
    vehicleManager: Id[VehicleManager],
    label: Label,
    vehicles: Quadtree
  ): Unit = {
    val timeBin = timeToBin(time, timeWindow)
    val key = (timeBin, taz.tazId, vehicleManager, label)
    val vehiclesInTAZ = vehicles
      .queryAll()
      .asScala
      .count(
        v =>
          taz == beamServices.tazTreeMap
            .getTAZ(v.asInstanceOf[BeamVehicle].spaceTime.loc.getX, v.asInstanceOf[BeamVehicle].spaceTime.loc.getY)
      )
    skimsPlus.put(key, skimsPlus.getOrElse(key, 0.0) + vehiclesInTAZ.toDouble)
  }

  def writeObservedSkimsPlus(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.observedSkimsPlusFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(observedSkimsPlusHeader.mkString(","))
    writer.write("\n")

    skimsPlus.foreach { keyVal =>
      val (bin, taz, vehicleManager, label) = keyVal._1
      writer.write(s"$bin,$taz,$vehicleManager,$label,$keyVal._2\n")
    }
    writer.close()
  }

  private def initialPreviousSkimsPlus(): TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double] = {
    if (beamConfig.beam.warmStart.enabled) {
      try {
        skimsPlusFilePath
          .map(BeamSkimmer.readSkimPlusFile)
          .getOrElse(TrieMap.empty)
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load previous skim from '${skimsFilePath}': ${ex.getMessage}", ex)
          TrieMap.empty
      }
    } else {
      TrieMap.empty
    }
  }
  // *********
}

object BeamSkimmer extends LazyLogging {
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
    count: Int,
    energy: Double
  ) {
    //NOTE: All times in seconds here
    def toSkimExternal: Skim = Skim(time.toInt, generalizedTime, generalizedCost, distance, cost, count, energy)
  }

  case class Skim(
    time: Int,
    generalizedTime: Double,
    generalizedCost: Double,
    distance: Double,
    cost: Double,
    count: Int,
    energy: Double
  )

  private def readCsvFile(filePath: String): TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = {
    val mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
    val res = TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal]()
    try {
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
        val energy = line.get("energy")

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
            numObservations.toInt,
            Option(energy).map(_.toDouble).getOrElse(0.0)
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

  // *******
  // Skim Plus
  private val observedSkimsPlusFileBaseName = "skimsPlus"
  private val observedSkimsPlusHeader = "time,taz,vehicleManager,availableVehicles,demand".split(",")
  type TimeBin = Int
  type Label = String
  case class SkimPlus(availableVehicles: Int = 0, demand: Int = 0)

  private def readSkimPlusFile(filePath: String): TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double] = {
    val mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
    val res = TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double]()
    try {
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val time = line.get("time")
        val tazid = line.get("taz")
        val vehicleManager = line.get("vehicleManager")
        val label = line.get("label")
        val value = line.get("value").toDouble
        res.put(
          (time.toInt, Id.create(tazid, classOf[TAZ]), Id.create(vehicleManager, classOf[VehicleManager]), label),
          value
        )
        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res
  }
  //
}
