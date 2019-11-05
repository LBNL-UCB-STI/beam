package beam.router.skim
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.ScalaEvent
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamTrip}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils

import scala.collection.immutable

class ODSkimmer(beamServices: BeamServices, h3taz: H3TAZ) extends AbstractSkimmer(beamServices, h3taz) {

  import ODSkimmer._
  import beamServices._

  private val Eol = "\n"
  private val aggregatedSkimsFileBaseName: String = "odskimsAggregated.csv.gz"
  private val CsvLineHeader: String =
  "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy" + Eol
  private val observedSkimsFileBaseName = "odskims"
  private val fullSkimsFileBaseName = "odskimsFull"
  private val excerptSkimsFileBaseName = "odskimsExcerpt"


  override val aggregatedSkimsFilePath: String = beamConfig.beam.warmStart.skimsFilePath

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: ODSkimmerEvent =>
        observeTrip(e.trip, e.generalizedTimeInHours, e.generalizedCost, e.energyConsumption)
      case _ =>
    }
  }

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.skimmanager.odskimmer.writeObservedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeObservedSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeObservedSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeObservedSkims(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(
        s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeFullSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeFullSkims(event)
      }
    }
    if (beamConfig.beam.skimmanager.odskimmer.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.odskimmer.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeAggregatedSkims(event)
      }
    }
  }

  override def fromCsv(
    line: immutable.Map[String, String]
  ): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    immutable.Map(
      ODSkimmerKey(
        line("timeBin").toInt,
        BeamMode.fromString("mode").get,
        Id.create(line("idTaz"), classOf[TAZ]),
        Id.create(line("idTaz"), classOf[TAZ])
      )
      -> ODSkimmerInternal(
        line("travelTimeInS").toDouble,
        line("generalizedTimeInS").toDouble,
        line("generalizedCost").toDouble,
        line("distanceInM").toDouble,
        line("cost").toDouble,
        line("numObservations").toInt,
        Option(line("energy")).map(_.toDouble).getOrElse(0.0)
      )
    )
  }

  override protected def getPastSkims: List[Map[AbstractSkimmerKey, AbstractSkimmerInternal]] = {
    ODSkimmer.pastSkims.map(
      list => list.map(kv => kv._1.asInstanceOf[AbstractSkimmerKey] -> kv._2.asInstanceOf[AbstractSkimmerInternal])
    )
  }

  override protected def getAggregatedSkim: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    ODSkimmer.aggregatedSkim.map(
      kv => kv._1.asInstanceOf[AbstractSkimmerKey] -> kv._2.asInstanceOf[AbstractSkimmerInternal]
    )
  }

  override protected def updatePastSkims(skims: List[Map[AbstractSkimmerKey, AbstractSkimmerInternal]]): Unit = {
    ODSkimmer.pastSkims =
      skims.map(list => list.map(kv => kv._1.asInstanceOf[ODSkimmerKey] -> kv._2.asInstanceOf[ODSkimmerInternal]))
  }

  override protected def updateAggregatedSkim(skim: Map[AbstractSkimmerKey, AbstractSkimmerInternal]): Unit = {
    ODSkimmer.aggregatedSkim = skim.map(kv => kv._1.asInstanceOf[ODSkimmerKey] -> kv._2.asInstanceOf[ODSkimmerInternal])
  }

  // *****
  // Helpers

  private def writeObservedSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      observedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    try {
      writer.write(CsvLineHeader + Eol)
      currentSkim.foreach(row => writer.write(row._1.toCsv + "," + row._2.toCsv + Eol))
    } finally {
      writer.close()
    }
  }

  private def writeAggregatedSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      aggregatedSkimsFileBaseName + ".csv.gz"
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    try {
      writer.write(CsvLineHeader + Eol)
      aggregatedSkim.foreach(row => writer.write(row._1.toCsv + "," + row._2.toCsv + Eol))
    } finally {
      writer.close()
    }
  }

  private def writeAllModeSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = BeamMode.allModes
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      excerptSkimsFileBaseName + ".csv.gz"
    )
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write(Eol)

    val weightedSkims = ProfilingUtils.timed("Get weightedSkims for modes", x => logger.info(x)) {
      modes.toParArray.flatMap { mode =>
        beamScenario.tazTreeMap.getTAZs.flatMap { origin =>
          beamScenario.tazTreeMap.getTAZs.flatMap { destination =>
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

  private def writeFullSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      fullSkimsFileBaseName + ".csv.gz"
    )
    val uniqueModes = currentSkim.map(keyVal => keyVal.asInstanceOf[ODSkimmerKey].mode).toList.distinct
    val uniqueTimeBins = 0 to 23

    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )

    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(CsvLineHeader)

    beamScenario.tazTreeMap.getTAZs
      .foreach { origin =>
        beamScenario.tazTreeMap.getTAZs.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim: ODSkimmer.Skim = currentSkim
                  .get(ODSkimmerKey(timeBin, mode, origin.tazId, destination.tazId))
                  .map(_.asInstanceOf[ODSkimmerInternal].toSkimExternal)
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
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count},${theSkim.energy}$Eol"
                )
              }
          }
        }
      }
    writer.close()
  }

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): Unit = {
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
    val origTaz = beamScenario.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = beamLegs.last
    val destCoord = geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamScenario.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = timeToBin(origLeg.startTime)
    val dist = beamLegs.map(_.travelPath.distanceInM).sum
    val key = ODSkimmerKey(timeBin, mode, origTaz, destTaz)
    val payload =
      ODSkimmerInternal(
        correctedTrip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInHours * 3600,
        generalizedCost,
        if (dist > 0.0) { dist } else { 1.0 },
        correctedTrip.costEstimate,
        1,
        energyConsumption
      )
    currentSkim.get(key) match {
      case Some(existingSkim: ODSkimmerInternal) =>
        val count = existingSkim.numObservations
        val newPayload = ((existingSkim * count + payload) / (count + 1)).asInstanceOf[ODSkimmerInternal]
        currentSkim.put(key, newPayload.copy(numObservations = count + 1))
      case _ =>
        currentSkim.put(key, payload)
    }
  }

  private def getExcerptData(
    timePeriodString: String,
    hoursIncluded: List[Int],
    origin: TAZ,
    destination: TAZ,
    mode: BeamMode,
    dummyId: Id[BeamVehicleType]
  ): ExcerptData = {
    val individualSkims = hoursIncluded.map { timeBin =>
      currentSkim
        .get(ODSkimmerKey(timeBin, mode, origin.tazId, destination.tazId))
        .map(_.asInstanceOf[ODSkimmerInternal].toSkimExternal)
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

}

object ODSkimmer extends LazyLogging {

  private var pastSkims: immutable.List[immutable.Map[ODSkimmerKey, ODSkimmerInternal]] = immutable.List()
  private var aggregatedSkim: immutable.Map[ODSkimmerKey, ODSkimmerInternal] = immutable.Map()

  def getSkimDefaultValue(
    mode: BeamMode,
    originUTM: Location,
    destinationUTM: Location,
    departureTime: Int,
    vehicleTypeId: Id[BeamVehicleType],
    beamServices: BeamServices
  ): Skim = {
    val beamScenario = beamServices.beamScenario
    val beamConfig = beamServices.beamConfig
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

  def getRideHailPoolingTimeAndCostRatios(
    origin: Location,
    destination: Location,
    departureTime: Int,
    vehicleTypeId: org.matsim.api.core.v01.Id[BeamVehicleType],
    beamServices: BeamServices
  ): (Double, Double) = {
    val tazTreeMap = beamServices.beamScenario.tazTreeMap
    val beamConfig = beamServices.beamConfig
    val origTaz = tazTreeMap.getTAZ(origin.getX, origin.getY).tazId
    val destTaz = tazTreeMap.getTAZ(destination.getX, destination.getY).tazId
    val solo = getSkimValue(departureTime, RIDE_HAIL, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.numObservations > 5 =>
        skimValue
      case _ =>
        val (travelDistance, travelTime) = distanceAndTime(RIDE_HAIL, origin, destination)
        ODSkimmerInternal(
          travelTimeInS = travelTime.toDouble,
          generalizedTimeInS = 0,
          generalizedCost = 0,
          distanceInM = travelDistance.toDouble,
          cost = getRideHailCost(RIDE_HAIL, travelDistance, travelTime, beamConfig),
          numObservations = 0,
          energy = 0.0
        )
    }
    val pooled = getSkimValue(departureTime, RIDE_HAIL_POOLED, origTaz, destTaz) match {
      case Some(skimValue) if skimValue.numObservations > 5 =>
        skimValue
      case _ =>
        ODSkimmerInternal(
          travelTimeInS = solo.travelTimeInS * 1.1,
          generalizedTimeInS = 0,
          generalizedCost = 0,
          distanceInM = solo.distanceInM,
          cost = getRideHailCost(RIDE_HAIL_POOLED, solo.distanceInM, solo.travelTimeInS, beamConfig),
          numObservations = 0,
          energy = 0.0
        )
    }
    val timeFactor = if (solo.travelTimeInS > 0.0) { pooled.travelTimeInS / solo.travelTimeInS } else { 1.0 }
    val costFactor = if (solo.cost > 0.0) { pooled.cost / solo.cost } else { 1.0 }
    (timeFactor, costFactor)
  }

  def getTimeDistanceAndCost(
                              originUTM: Location,
                              destinationUTM: Location,
                              departureTime: Int,
                              mode: BeamMode,
                              vehicleTypeId: Id[BeamVehicleType],
                              beamServices: BeamServices
                            ): Skim = {
    val origTaz = beamServices.beamScenario.tazTreeMap.getTAZ(originUTM.getX, originUTM.getY).tazId
    val destTaz = beamServices.beamScenario.tazTreeMap.getTAZ(destinationUTM.getX, destinationUTM.getY).tazId
    getSkimValue(departureTime, mode, origTaz, destTaz) match {
      case Some(skimValue) =>
        skimValue.toSkimExternal
      case None =>
        getSkimDefaultValue(
          mode,
          originUTM,
          new Coord(destinationUTM.getX, destinationUTM.getY),
          departureTime,
          vehicleTypeId,
          beamServices
        )
    }
  }


  // cases
  case class ODSkimmerKey(timeBin: Int, mode: BeamMode, originTaz: Id[TAZ], destinationTaz: Id[TAZ])
      extends AbstractSkimmerKey {
    override def toCsv: String = timeBin + "," + mode + "," + originTaz + "," + destinationTaz
  }
  case class ODSkimmerInternal(
    travelTimeInS: Double,
    generalizedTimeInS: Double,
    generalizedCost: Double,
    distanceInM: Double,
    cost: Double,
    numObservations: Int,
    energy: Double
  ) extends AbstractSkimmerInternal {

    //NOTE: All times in seconds here
    def toSkimExternal: Skim =
      Skim(travelTimeInS.toInt, generalizedTimeInS, generalizedCost, distanceInM, cost, numObservations, energy)

    def +(that: AbstractSkimmerInternal): AbstractSkimmerInternal =
      ODSkimmerInternal(
        this.travelTimeInS + that.asInstanceOf[ODSkimmerInternal].travelTimeInS,
        this.generalizedTimeInS + that.asInstanceOf[ODSkimmerInternal].generalizedTimeInS,
        this.generalizedCost + that.asInstanceOf[ODSkimmerInternal].generalizedCost,
        this.distanceInM + that.asInstanceOf[ODSkimmerInternal].distanceInM,
        this.cost + that.asInstanceOf[ODSkimmerInternal].cost,
        this.numObservations + that.asInstanceOf[ODSkimmerInternal].numObservations,
        this.energy + that.asInstanceOf[ODSkimmerInternal].energy
      )

    def /(thatInt: Int): AbstractSkimmerInternal =
      ODSkimmerInternal(
        this.travelTimeInS / thatInt,
        this.generalizedTimeInS / thatInt,
        this.generalizedCost / thatInt,
        this.distanceInM / thatInt,
        this.cost / thatInt,
        this.numObservations / thatInt,
        this.energy / thatInt
      )

    def *(thatInt: Int): AbstractSkimmerInternal =
      ODSkimmerInternal(
        this.travelTimeInS * thatInt,
        this.generalizedTimeInS * thatInt,
        this.generalizedCost * thatInt,
        this.distanceInM * thatInt,
        this.cost * thatInt,
        this.numObservations * thatInt,
        this.energy * thatInt
      )
    override def toCsv: String =
      travelTimeInS + "," + generalizedTimeInS + "," + generalizedCost + "," + distanceInM + "," + cost + "," + numObservations + "," + energy
  }

  case class ODSkimmerEvent(
    time: Double,
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ) extends Event(time)
      with ScalaEvent {
    override def getEventType: String = "ODSkimmerEvent"
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

  // *** helpers ***

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

  def timeToBin(departTime: Int): Int = {
    Math.floorMod(Math.floor(departTime.toDouble / 3600.0).toInt, 24)
  }

  def timeToBin(departTime: Int, timeWindow: Int): Int = departTime / timeWindow

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

  private def getRideHailCost(
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

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[ODSkimmerInternal] = {
    pastSkims.map(_.get(ODSkimmerKey(timeToBin(time), mode, orig, dest))).headOption match {
      case Some(somePastSkim) => somePastSkim
      case _                  => aggregatedSkim.get(ODSkimmerKey(timeToBin(time), mode, orig, dest))
    }
  }
}
