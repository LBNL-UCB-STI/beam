package beam.router.skim
import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, CAV, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_POOLED, RIDE_HAIL_TRANSIT, TRANSIT, WALK_TRANSIT}
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.utils.io.IOUtils

import scala.collection.immutable

class ODSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim$Elm) extends AbstractSkimmer(beamServices, config) {

  import ODSkimmer._
  import beamServices._

  ODSkimmer.h3taz = beamServices.beamScenario.h3taz
  override protected def skimFileBaseName: String = ODSkimmer.fileBaseName
  override protected def skimFileHeader: String = ODSkimmer.csvLineHeader
  override protected def getEventType: String = ODSkimmer.eventType

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    super.writeToDisk(event)
    if (beamConfig.beam.abstractSkimmer.odSkimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % beamConfig.beam.abstractSkimmer.odSkimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(
        s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (beamConfig.beam.abstractSkimmer.odSkimmer.writeFullSkimsInterval > 0 && event.getIteration % beamConfig.beam.abstractSkimmer.odSkimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", x => logger.info(x)) {
        writeFullSkims(event)
      }
    }
  }

  override def fromCsv(
    line: immutable.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        line("timeBin").toInt,
        BeamMode.fromString("mode").get,
        Id.create(line("idTaz"), classOf[TAZ]),
        Id.create(line("idTaz"), classOf[TAZ])
      ),
      ODSkimmerInternal(
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

  override protected def publishReadOnlySkims(): Unit = {
    ODSkimmer.rdOnlyPastSkims =
      pastSkims.map(_.map(kv => kv._1.asInstanceOf[ODSkimmerKey] -> kv._2.asInstanceOf[ODSkimmerInternal])).toArray
    ODSkimmer.rdOnlyAggregatedSkim =
      aggregatedSkim.map(kv => kv._1.asInstanceOf[ODSkimmerKey] -> kv._2.asInstanceOf[ODSkimmerInternal])
  }



  // *****
  // Helpers

  private def writeAllModeSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = BeamMode.allModes
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      ODSkimmer.fileBaseName + "Excerpt.csv.gz"
    )
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(fileHeader)
    writer.write("\n")

    val skim = currentSkim.map(kv => kv._1.asInstanceOf[ODSkimmerKey] -> kv._2.asInstanceOf[ODSkimmerInternal]).toMap

    val weightedSkims = ProfilingUtils.timed("Get weightedSkims for modes", x => logger.info(x)) {
      modes.toParArray.flatMap { mode =>
        beamScenario.tazTreeMap.getTAZs.flatMap { origin =>
          beamScenario.tazTreeMap.getTAZs.flatMap { destination =>
            val am = ODSkimmerUtils.getExcerptData(
              "AM",
              morningPeakHours,
              origin,
              destination,
              mode,
              dummyId,
              skim,
              beamServices
            )
            val pm = ODSkimmerUtils.getExcerptData(
              "PM",
              afternoonPeakHours,
              origin,
              destination,
              mode,
              dummyId,
              skim,
              beamServices
            )
            val offPeak = ODSkimmerUtils.getExcerptData(
              "OffPeak",
              nonPeakHours,
              origin,
              destination,
              mode,
              dummyId,
              skim,
              beamServices
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
      ODSkimmer.fileBaseName + "Full.csv.gz"
    )
    val uniqueModes = currentSkim.map(keyVal => keyVal.asInstanceOf[ODSkimmerKey].mode).toList.distinct
    val uniqueTimeBins = 0 to 23

    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )

    val writer = IOUtils.getBufferedWriter(filePath)
    writer.write(csvLineHeader)

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
                  s"$timeBin,$mode,${origin.tazId},${destination.tazId},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedTime},${theSkim.distance},${theSkim.count},${theSkim.energy}\n"
                )
              }
          }
        }
      }
    writer.close()
  }

}

object ODSkimmer extends LazyLogging {
  import ODSkimmerUtils._

  private val csvLineHeader: String =
    "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy\n"
  private[skim] val eventType: String = "ODSkimmerEvent"
  private val fileBaseName: String = "skimsOD"
  private var rdOnlyPastSkims: Array[immutable.Map[ODSkimmerKey, ODSkimmerInternal]] = Array()
  private var rdOnlyAggregatedSkim: immutable.Map[ODSkimmerKey, ODSkimmerInternal] = immutable.Map()
  private var h3taz: H3TAZ = _

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

  private def getSkimValue(time: Int, mode: BeamMode, orig: Id[TAZ], dest: Id[TAZ]): Option[ODSkimmerInternal] = {
    rdOnlyPastSkims
      .map(_.get(ODSkimmerKey(timeToBin(time), mode, orig, dest)))
      .headOption
      .getOrElse(rdOnlyAggregatedSkim.get(ODSkimmerKey(timeToBin(time), mode, orig, dest)))
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

    override def aggregateOverIterations(
      nbOfIterations: Int,
      newSkim: Option[_ <: AbstractSkimmerInternal]
    ): AbstractSkimmerInternal = {
      newSkim match {
        case Some(skim: ODSkimmerInternal) =>
          ODSkimmerInternal(
            travelTimeInS = ((this.travelTimeInS * nbOfIterations) + skim.travelTimeInS) / (nbOfIterations + 1),
            generalizedTimeInS = ((this.generalizedTimeInS * nbOfIterations) + skim.generalizedTimeInS) / (nbOfIterations + 1),
            generalizedCost = ((this.generalizedCost * nbOfIterations) + skim.generalizedCost) / (nbOfIterations + 1),
            distanceInM = ((this.distanceInM * nbOfIterations) + skim.distanceInM) / (nbOfIterations + 1),
            cost = ((this.cost * nbOfIterations) + skim.cost) / (nbOfIterations + 1),
            numObservations = nbOfIterations + 1,
            energy = ((this.energy * nbOfIterations) + skim.energy) / (nbOfIterations + 1),
          )
        case _ =>
          ODSkimmerInternal(
            travelTimeInS = (this.travelTimeInS * nbOfIterations) / (nbOfIterations + 1),
            generalizedTimeInS = (this.generalizedTimeInS * nbOfIterations) / (nbOfIterations + 1),
            generalizedCost = (this.generalizedCost * nbOfIterations) / (nbOfIterations + 1),
            distanceInM = (this.distanceInM * nbOfIterations) / (nbOfIterations + 1),
            cost = (this.cost * nbOfIterations) / (nbOfIterations + 1),
            numObservations = nbOfIterations + 1,
            energy = (this.energy * nbOfIterations) / (nbOfIterations + 1),
          )
      }
    }

    override def toCsv: String =
      travelTimeInS + "," + generalizedTimeInS + "," + generalizedCost + "," + distanceInM + "," + cost + "," + numObservations + "," + energy

    override def aggregateByKey(newSkim: Option[_ <: AbstractSkimmerInternal]): AbstractSkimmerInternal = {
      newSkim match {
        case Some(skim: ODSkimmerInternal) =>
          ODSkimmerInternal(
            travelTimeInS = ((this.travelTimeInS * this.numObservations) + skim.travelTimeInS) / (this.numObservations + 1),
            generalizedTimeInS = ((this.generalizedTimeInS * this.numObservations) + skim.generalizedTimeInS) / (this.numObservations + 1),
            generalizedCost = ((this.generalizedCost * this.numObservations) + skim.generalizedCost) / (this.numObservations + 1),
            distanceInM = ((this.distanceInM * this.numObservations) + skim.distanceInM) / (this.numObservations + 1),
            cost = ((this.cost * this.numObservations) + skim.cost) / (this.numObservations + 1),
            numObservations = this.numObservations + 1,
            energy = ((this.energy * this.numObservations) + skim.energy) / (this.numObservations + 1),
          )
        case _ =>
          ODSkimmerInternal(
            travelTimeInS = (this.travelTimeInS * this.numObservations) / (this.numObservations + 1),
            generalizedTimeInS = (this.generalizedTimeInS * this.numObservations) / (this.numObservations + 1),
            generalizedCost = (this.generalizedCost * this.numObservations) / (this.numObservations + 1),
            distanceInM = (this.distanceInM * this.numObservations) / (this.numObservations + 1),
            cost = (this.cost * this.numObservations) / (this.numObservations + 1),
            numObservations = this.numObservations + 1,
            energy = (this.energy * this.numObservations) / (this.numObservations + 1),
          )
      }
    }
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
}
