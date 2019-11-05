package beam.router

import java.io.File

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.taz.TAZ
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
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.vehiclesharing.VehicleManager
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.language.implicitConversions
import scala.util.control.NonFatal

//TODO to be validated against google api
class BeamSkimmer @Inject()(
  val beamServices: BeamServices,
  val beamScenario: BeamScenario,
  val geo: GeoUtils
) extends LazyLogging {
  import BeamSkimmer._
  import beamScenario._

  def beamConfig: BeamConfig = beamServices.beamConfig

  // The OD/Mode/Time Matrix
  private var previousSkims: BeamSkimmerADT = initialPreviousSkims()
  private var skims: BeamSkimmerADT = TrieMap()

  private def skimsFilePath: Option[String] = {
    val filePath = beamConfig.beam.warmStart.skimsFilePath
    if (new File(filePath).isFile) {
      Some(filePath)
    } else {
      None
    }
  }

  private def initialPreviousSkims(): TrieMap[(Int, BeamMode, Id[TAZ], Id[TAZ]), SkimInternal] = {
    if (beamConfig.beam.warmStart.enabled) {
      try {
        val previousSkims = skimsFilePath
          .map(BeamSkimmer.fromCsv)
          .getOrElse(TrieMap.empty)
        logger.info(s"Previous skims successfully loaded from path '${skimsFilePath.getOrElse("NO PATH FOUND")}'")
        previousSkims
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load previous skim from '$skimsFilePath': ${ex.getMessage}", ex)
          TrieMap.empty
      }
    } else {
      TrieMap.empty
    }
  }

  def getSkimDefaultValue(
    mode: BeamMode,
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    vehicleTypeId: Id[BeamVehicleType]
  ): Skim = {
    val (travelDistance, travelTime) = distanceAndTime(mode, originUTM, destinationUTM)
    val travelCost: Double = mode match {
      case CAR | CAV =>
        DrivingCost.estimateDrivingCost(
          new BeamLeg(
            departureTime,
            mode,
            travelTime,
            new BeamPath(IndexedSeq(), IndexedSeq(), None, null, null, travelDistance)
          ),
          beamScenario.vehicleTypes(vehicleTypeId),
          beamScenario.fuelTypePrices
        )
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * travelTime / 60.0
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * travelDistance / 1609.0 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * travelTime / 60.0
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
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType]
  ): Skim = {
    val origTaz = tazTreeMap.getTAZ(originUTM.getX, originUTM.getY).tazId
    val destTaz = tazTreeMap.getTAZ(destinationUTM.getX, destinationUTM.getY).tazId
    getSkimValue(departureTime, mode, origTaz, destTaz) match {
      case Some(skimValue) =>
        skimValue.toSkimExternal
      case None =>
        getSkimDefaultValue(
          mode,
          originUTM,
          new Coord(destinationUTM.getX, destinationUTM.getY),
          departureTime,
          vehicleTypeId
        )
    }
  }

  private def getRideHailCost(mode: BeamMode, distanceInMeters: Double, timeInSeconds: Double): Double = {
    mode match {
      case RIDE_HAIL =>
        beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.defaultCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.defaultBaseCost
      case RIDE_HAIL_POOLED =>
        beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMile * distanceInMeters / 1609.34 + beamConfig.beam.agentsim.agents.rideHail.pooledCostPerMinute * timeInSeconds / 60 + beamConfig.beam.agentsim.agents.rideHail.pooledBaseCost
      case _ =>
        0.0
    }

  }

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType]
  ): (Double, Double) = {
    val origTaz = tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        val (travelDistance, travelTime) = distanceAndTime(RIDE_HAIL, origin, destination)
        SkimInternal(
          time = travelTime.toDouble,
          generalizedTime = 0,
          generalizedCost = 0,
          distance = travelDistance.toDouble,
          cost = getRideHailCost(RIDE_HAIL, travelDistance, travelTime),
          count = 0,
          energy = 0.0
        )
    }
    val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.count > 5 =>
        skimValue
      case _ =>
        SkimInternal(
          time = solo.time * 1.1,
          generalizedTime = 0,
          generalizedCost = 0,
          distance = solo.distance,
          cost = getRideHailCost(RIDE_HAIL_POOLED, solo.distance, solo.time),
          count = 0,
          energy = 0.0
        )
    }
    val timeFactor = if (solo.time > 0.0) { pooled.time / solo.time } else { 1.0 }
    val costFactor = if (solo.cost > 0.0) { pooled.cost / solo.cost } else { 1.0 }
    (timeFactor, costFactor)
  }

  private def distanceAndTime(mode: BeamMode, originUTM: Location, destinationUTM: Location) = {
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
    energyConsumption: Double
  ): Option[SkimInternal] = {
    val mode = trip.tripClassifier
    val correctedTrip = mode match {
      case WALK =>
        trip
      case _ =>
        val legs = trip.legs.drop(1).dropRight(1)
        EmbodiedBeamTrip(legs)
    }
    val beamLegs = correctedTrip.beamLegs
    val origLeg = beamLegs.head
    val origCoord = geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = beamLegs.last
    val destCoord = geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = timeToBin(origLeg.startTime)
    val dist = beamLegs.map(_.travelPath.distanceInM).sum
    val key = (timeBin, mode, origTaz, destTaz)
    val payload =
      SkimInternal(
        correctedTrip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInHours * 3600,
        generalizedCost,
        if (dist > 0.0) { dist } else { 1.0 },
        correctedTrip.costEstimate,
        1,
        energyConsumption
      )
    skims.get(key) match {
      case Some(existingSkim) =>
        val newPayload = SkimInternal(
          time = mergeAverage(existingSkim.time, existingSkim.count, payload.time),
          generalizedTime = mergeAverage(existingSkim.generalizedTime, existingSkim.count, payload.generalizedTime),
          generalizedCost = mergeAverage(existingSkim.generalizedCost, existingSkim.count, payload.generalizedCost),
          distance = mergeAverage(existingSkim.distance, existingSkim.count, payload.distance),
          cost = mergeAverage(existingSkim.cost, existingSkim.count, payload.cost),
          count = existingSkim.count + 1,
          energy = mergeAverage(existingSkim.energy, existingSkim.count, payload.energy)
        )
        skims.put(key, newPayload)
      case None =>
        skims.put(key, payload)
    }
  }

  def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def timeToBin(departTime: Int, timeWindow: Int): Int = departTime / timeWindow

  def mergeAverage(existingAverage: Double, existingCount: Int, newValue: Double): Double = {
    (existingAverage * existingCount + newValue) / (existingCount + 1)
  }

  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.beamskimmer.writeObservedSkimsInterval > 0 && event.getIteration % beamConfig.beam.beamskimmer.writeObservedSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeObservedSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeObservedSkims(event)
      }
    }
    if (beamConfig.beam.beamskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % beamConfig.beam.beamskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(
        s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (beamConfig.beam.beamskimmer.writeObservedSkimsPlusInterval > 0 && event.getIteration % beamConfig.beam.beamskimmer.writeObservedSkimsPlusInterval == 0) {
      ProfilingUtils.timed(s"writeObservedSkimsPlus on iteration ${event.getIteration}", x => logger.info(x)) {
        writeObservedSkimsPlus(event)
      }
    }
    if (beamConfig.beam.beamskimmer.writeFullSkimsInterval > 0 && event.getIteration % beamConfig.beam.beamskimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeFullSkims(event)
      }
    }
    previousSkims = skims
    skims = new TrieMap()
    previousSkimsPlus = skimsPlus
    skimsPlus = new TrieMap()
    trackSkimsPlusTS = -1
  }

  def getExcerptData(
    timePeriodString: String,
    hoursIncluded: List[Int],
    origin: TAZ,
    destination: TAZ,
    mode: BeamMode,
    dummyId: Id[BeamVehicleType]
  ): ExcerptData = {
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
            dummyId
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

    ExcerptData(
      timePeriodString = timePeriodString,
      mode = mode,
      originTazId = origin.tazId,
      destinationTazId = destination.tazId,
      weightedTime = weightedTime,
      weightedGeneralizedTime = weightedGeneralizedTime,
      weightedCost = weightedCost,
      weightedGeneralizedCost = weightedGeneralizedCost,
      weightedDistance = weightedDistance,
      sumWeights = sumWeights,
      weightedEnergy = weightedEnergy
    )
  }

  def writeAllModeSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = BeamMode.allModes
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.excerptSkimsFileBaseName + ".csv.gz"
    )
    val dummyId = Id.create(
      beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write(Eol)

    val weightedSkims = ProfilingUtils.timed("Get weightedSkims for modes", x => logger.info(x)) {
      modes.toParArray.flatMap { mode =>
        tazTreeMap.getTAZs.flatMap { origin =>
          tazTreeMap.getTAZs.flatMap { destination =>
            val am = getExcerptData(
              "AM",
              morningPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            val pm = getExcerptData(
              "PM",
              afternoonPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            val offPeak = getExcerptData(
              "OffPeak",
              nonPeakHours,
              origin,
              destination,
              mode,
              dummyId
            )
            List(am, pm, offPeak)
          }
        }
      }
    }
    logger.info(s"weightedSkims size: ${weightedSkims.size}")

    weightedSkims.seq.foreach { ws: ExcerptData =>
      writer.write(
        s"${ws.timePeriodString},${ws.mode},${ws.originTazId},${ws.destinationTazId},${ws.weightedTime},${ws.weightedGeneralizedTime},${ws.weightedCost},${ws.weightedGeneralizedCost},${ws.weightedDistance},${ws.sumWeights},${ws.weightedEnergy}\n"
      )
    }
    writer.close()
  }

  def writeFullSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.fullSkimsFileBaseName + ".csv.gz"
    )
    val uniqueModes = skims.map(keyVal => keyVal._1._2).toList.distinct
    val uniqueTimeBins = 0 to 23

    val dummyId = Id.create(
      beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )

    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(CsvLineHeader)

    tazTreeMap.getTAZs
      .foreach { origin =>
        tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim: Skim = getSkimValue(timeBin * 3600, mode, origin.tazId, destination.tazId)
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
                        dummyId
                      )
                    } else {
                      getSkimDefaultValue(
                        mode,
                        origin.coord,
                        destination.coord,
                        timeBin * 3600,
                        dummyId
                      )
                    }
                  }

                writer.write(
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count},${theSkim.energy}$Eol"
                )
              }
          }
        }
      }
    writer.close()
  }

  def writeObservedSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.observedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    try {
      toCsv(skims).foreach(writer.write)
    } finally {
      writer.close()
    }
  }

  // **********
  // Skim Plus
  private var trackSkimsPlusTS = -1
  private var previousSkimsPlus: TrieMap[BeamSkimmerPlusKey, Double] =
    initialPreviousSkimsPlus()
  private var skimsPlus: TrieMap[BeamSkimmerPlusKey, Double] = TrieMap()
  private def skimsPlusFilePath: Option[String] = {
    val filePath = beamConfig.beam.warmStart.skimsPlusFilePath
    if (new File(filePath).isFile) {
      Some(filePath)
    } else {
      None
    }
  }

  def getPreviousSkimPlusValues(
    fromBin: Int,
    untilBin: Int,
    taz: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    label: Label
  ): Vector[Double] = {
    (fromBin until untilBin)
      .map { i =>
        previousSkimsPlus.get((i, taz, vehicleManager, label))
      }
      .toVector
      .flatten
  }

  def countEventsByTAZ(
    curBin: Int,
    location: Coord,
    vehicleManager: Id[VehicleManager],
    label: Label,
    count: Int = 1
  ): Unit = {
    if (curBin > trackSkimsPlusTS) trackSkimsPlusTS = curBin
    val taz = tazTreeMap.getTAZ(location.getX, location.getY)
    val key = (trackSkimsPlusTS, taz.tazId, vehicleManager, label)
    skimsPlus.put(key, skimsPlus.getOrElse(key, 0.0) + count.toDouble)
  }

  def countEvents(
    curBin: Int,
    tazId: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    label: Label,
    count: Int = 1
  ): Unit = {
    if (curBin > trackSkimsPlusTS) trackSkimsPlusTS = curBin
    val key = (trackSkimsPlusTS, tazId, vehicleManager, label)
    skimsPlus.put(key, skimsPlus.getOrElse(key, 0.0) + count.toDouble)
  }

  def addValue(
    curBin: Int,
    tazId: Id[TAZ],
    vehicleManager: Id[VehicleManager],
    label: Label,
    value: Double
  ): Option[Double] = {
    if (curBin > trackSkimsPlusTS) trackSkimsPlusTS = curBin
    val key = (trackSkimsPlusTS, tazId, vehicleManager, label)
    skimsPlus.put(key, skimsPlus.getOrElse(key, 0.0) + value)
  }

  def observeVehicleAvailabilityByTAZ(
    curBin: Int,
    vehicleManager: Id[VehicleManager],
    label: Label,
    vehicles: List[Any]
  ): Unit = {
    var filteredVehicles = vehicles
    tazTreeMap.getTAZs.foreach { taz =>
      val filteredVehiclesTemp = filteredVehicles.filter(
        v =>
          taz != tazTreeMap
            .getTAZ(v.asInstanceOf[BeamVehicle].spaceTime.loc.getX, v.asInstanceOf[BeamVehicle].spaceTime.loc.getY)
      )
      val count = filteredVehicles.size - filteredVehiclesTemp.size
      countEventsByTAZ(curBin, taz.coord, vehicleManager, label, count)
      filteredVehicles = filteredVehiclesTemp
    }
  }

  def writeObservedSkimsPlus(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      BeamSkimmer.observedSkimsPlusFileBaseName
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(observedSkimsPlusHeader.mkString(","))
    writer.write("\n")

    skimsPlus.foreach {
      case (k, v) =>
        val (bin, taz, vehicleManager, label) = k
        writer.write(s"$bin,$taz,$vehicleManager,$label,$v\n")
    }
    writer.close()
  }

  private def initialPreviousSkimsPlus(): TrieMap[BeamSkimmerPlusKey, Double] = {
    if (beamConfig.beam.warmStart.enabled) {
      try {
        skimsPlusFilePath
          .map(BeamSkimmer.readSkimPlusFile)
          .getOrElse(TrieMap.empty)
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load previous skim from '$skimsPlusFilePath': ${ex.getMessage}", ex)
          TrieMap.empty
      }
    } else {
      TrieMap.empty
    }
  }
  // *********
}

object BeamSkimmer extends LazyLogging {
  type BeamSkimmerKey = (Int, BeamMode, Id[TAZ], Id[TAZ])
  type BeamSkimmerADT = TrieMap[BeamSkimmerKey, SkimInternal]

  val Eol = "\n"

  val CsvLineHeader: String =
  "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy" + Eol

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

  case class ExcerptData(
    timePeriodString: String,
    mode: BeamMode,
    originTazId: Id[TAZ],
    destinationTazId: Id[TAZ],
    weightedTime: Double,
    weightedGeneralizedTime: Double,
    weightedCost: Double,
    weightedGeneralizedCost: Double,
    weightedDistance: Double,
    sumWeights: Double,
    weightedEnergy: Double
  )

  private[router] def fromCsv(filePath: String): BeamSkimmerADT = {
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

  private[router] def toCsv(content: BeamSkimmerADT): Iterator[String] = {
    val contentIterator = content.toIterator
      .map { keyVal =>
        Seq(
          keyVal._1._1,
          keyVal._1._2,
          keyVal._1._3,
          keyVal._1._4,
          keyVal._2.time,
          keyVal._2.generalizedTime,
          keyVal._2.cost,
          keyVal._2.generalizedCost,
          keyVal._2.distance,
          keyVal._2.count,
          keyVal._2.energy
        ).mkString("", ",", Eol)
      }
    Iterator.single(CsvLineHeader) ++ contentIterator
  }

  // *******
  // Skim Plus
  type BeamSkimmerPlusKey = (TimeBin, Id[TAZ], Id[VehicleManager], Label)
  type TimeBin = Int
  type Label = String

  private val observedSkimsPlusFileBaseName = "skimsPlus.csv.gz"
  private val observedSkimsPlusHeader = "time,taz,manager,label,value".split(",")

  private def readSkimPlusFile(filePath: String): TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double] = {
    val mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
    val res = TrieMap[(TimeBin, Id[TAZ], Id[VehicleManager], Label), Double]()
    try {
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val time = line.get("time")
        val tazId = line.get("taz")
        val manager = line.get("manager")
        val label = line.get("label")
        val value = line.get("value").toDouble
        res.put(
          (time.toInt, Id.create(tazId, classOf[TAZ]), Id.create(manager, classOf[VehicleManager]), label),
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
  // ********
}
