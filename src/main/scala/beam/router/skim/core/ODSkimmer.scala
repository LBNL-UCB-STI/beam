package beam.router.skim.core

import java.io.BufferedWriter
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.{readonly, GeoUnit, Skims}
import beam.router.skim.readonly.ODSkims
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationEndsEvent

import scala.util.control.NonFatal

class ODSkimmer @Inject()(matsimServices: MatsimServices, beamScenario: BeamScenario, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {

  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim
  import ODSkimmer._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = readonly.ODSkims(beamConfig, beamScenario)
  import readOnlySkim._

  override protected val skimName: String = config.origin_destination_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.OD_SKIMMER
  override protected val skimFileBaseName: String = config.origin_destination_skimmer.fileBaseName
  override protected val skimFileHeader: String =
    "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,level4CavTravelTimeScalingFactor,observations,iterations"

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    super.writeToDisk(event)
    if (config.origin_destination_skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % config.origin_destination_skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(
        s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}",
        v => logger.info(v)
      ) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (config.origin_destination_skimmer.writeFullSkimsInterval > 0 && event.getIteration % config.origin_destination_skimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", v => logger.info(v)) {
        val filePath = event.getServices.getControlerIO.getIterationFilename(
          event.getServices.getIterationNumber,
          skimFileBaseName + "Full.csv.gz"
        )
        val uniqueTimeBins: Seq[Int] = 0 to 23
        val origins = beamScenario.tazTreeMap.getTAZs
          .map(taz => GeoUnit.TAZ(taz.tazId.toString, taz.coord, taz.areaInSquareMeters))
          .toSeq
        // Yes, we pass origin also as destinations because we want skims between all possible taz pairs
        writeFullSkims(origins, origins, uniqueTimeBins, filePath)
      }
    }
  }

  override def fromCsv(
    row: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    ODSkimmer.fromCsv(row)
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[ODSkimmerInternal])
      .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, 1, observations = 0))
    val currSkim =
      currIteration
        .map(_.asInstanceOf[ODSkimmerInternal])
        .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, 1, observations = 0, iterations = 1))
    ODSkimmerInternal(
      travelTimeInS = (prevSkim.travelTimeInS * prevSkim.iterations + currSkim.travelTimeInS * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      generalizedTimeInS = (prevSkim.generalizedTimeInS * prevSkim.iterations + currSkim.generalizedTimeInS * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      generalizedCost = (prevSkim.generalizedCost * prevSkim.iterations + currSkim.generalizedCost * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      distanceInM = (prevSkim.distanceInM * prevSkim.iterations + currSkim.distanceInM * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      cost = (prevSkim.cost * prevSkim.iterations + currSkim.cost * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      energy = (prevSkim.energy * prevSkim.iterations + currSkim.energy * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      level4CavTravelTimeScalingFactor = (prevSkim.level4CavTravelTimeScalingFactor * prevSkim.iterations + currSkim.level4CavTravelTimeScalingFactor * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      observations = (prevSkim.observations * prevSkim.iterations + currSkim.observations * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[ODSkimmerInternal])
      .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, 1, observations = 0))
    val currSkim = currObservation.asInstanceOf[ODSkimmerInternal]
    ODSkimmerInternal(
      travelTimeInS = (prevSkim.travelTimeInS * prevSkim.observations + currSkim.travelTimeInS * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      generalizedTimeInS = (prevSkim.generalizedTimeInS * prevSkim.observations + currSkim.generalizedTimeInS * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      generalizedCost = (prevSkim.generalizedCost * prevSkim.observations + currSkim.generalizedCost * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      distanceInM = (prevSkim.distanceInM * prevSkim.observations + currSkim.distanceInM * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      cost = (prevSkim.cost * prevSkim.observations + currSkim.cost * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      energy = (prevSkim.energy * prevSkim.observations + currSkim.energy * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      level4CavTravelTimeScalingFactor = (prevSkim.level4CavTravelTimeScalingFactor * prevSkim.observations + currSkim.level4CavTravelTimeScalingFactor * currSkim.observations) / (prevSkim.observations + currSkim.observations),
      observations = prevSkim.observations + currSkim.observations,
      iterations = matsimServices.getIterationNumber + 1
    )
  }

  // *****
  // Helpers
  private def writeAllModeSkimsForPeakNonPeakPeriods(event: IterationEndsEvent): Unit = {
    val morningPeakHours = (7 to 8).toList
    val afternoonPeakHours = (15 to 16).toList
    val nonPeakHours = (0 to 6).toList ++ (9 to 14).toList ++ (17 to 23).toList
    val modes = BeamMode.allModes
    val fileHeader =
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,observations,energy"
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      skimFileBaseName + "Excerpt.csv.gz"
    )
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(fileHeader)
      writer.write("\n")

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
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write skim in '$filePath': ${ex.getMessage}", ex)
    } finally {
      if (null != writer)
        writer.close()
    }
  }

  protected def writeFullSkims(
    origins: Seq[GeoUnit],
    destinations: Seq[GeoUnit],
    uniqueTimeBins: Seq[Int],
    filePath: String
  ): Unit = {
    val uniqueModes = currentSkim.keys.collect { case e: ODSkimmerKey => e.mode }.toList.distinct
    require(uniqueModes.nonEmpty, s"Expected to get ODSkimmerKey which contains modes")
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    val vehicleType: BeamVehicleType = beamScenario.vehicleTypes(dummyId)
    val fuelPrice = beamScenario.fuelTypePrices(vehicleType.primaryFuelType)

    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(skimFileHeader + "\n")

      origins.foreach { origin =>
        destinations.foreach { destination =>
          uniqueModes.foreach { mode =>
            uniqueTimeBins
              .foreach { timeBin =>
                val theSkim: ODSkimmer.Skim =
                  getCurrentSkimValue(ODSkimmerKey(timeBin, mode, origin.id, destination.id))
                    .map(_.asInstanceOf[ODSkimmerInternal].toSkimExternal)
                    .getOrElse {
                      if (origin.equals(destination)) {
                        val newDestCoord = new Coord(
                          origin.center.getX,
                          origin.center.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
                        )
                        readOnlySkim
                          .asInstanceOf[ODSkims]
                          .getSkimDefaultValue(
                            mode,
                            origin.center,
                            newDestCoord,
                            timeBin * 3600,
                            dummyId,
                            vehicleType,
                            fuelPrice,
                            beamScenario
                          )
                      } else {
                        readOnlySkim
                          .asInstanceOf[ODSkims]
                          .getSkimDefaultValue(
                            mode,
                            origin.center,
                            destination.center,
                            timeBin * 3600,
                            dummyId,
                            vehicleType,
                            fuelPrice,
                            beamScenario
                          )
                      }
                    }

                // "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,level4CavTravelTimeScalingFactor,observations,iterations"
                writer.write(
                  s"$timeBin,$mode,${origin.id},${destination.id},${theSkim.time},${theSkim.generalizedTime},${theSkim.cost},${theSkim.generalizedCost},${theSkim.distance},${theSkim.energy},${theSkim.level4CavTravelTimeScalingFactor},${theSkim.count}\n"
                )
              }
          }
        }
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write skim in '$filePath': ${ex.getMessage}", ex)
    } finally {
      if (null != writer)
        writer.close()
    }
  }

  def getExcerptData(
    timePeriodString: String,
    hoursIncluded: List[Int],
    origin: TAZ,
    destination: TAZ,
    mode: BeamMode,
    dummyId: Id[BeamVehicleType]
  ): ExcerptData = {
    import scala.language.implicitConversions
    val individualSkims = hoursIncluded.map { timeBin =>
      getCurrentSkimValue(ODSkimmerKey(timeBin, mode, origin.tazId.toString, destination.tazId.toString))
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
          readOnlySkim
            .asInstanceOf[ODSkims]
            .getSkimDefaultValue(
              mode,
              origin.coord,
              adjustedDestCoord,
              timeBin * 3600,
              dummyId,
              beamScenario.vehicleTypes(dummyId),
              beamScenario.fuelTypePrices(beamScenario.vehicleTypes(dummyId).primaryFuelType),
              beamScenario
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
    val weightedLevel4TravelTimeScale = individualSkims
      .map(_.level4CavTravelTimeScalingFactor)
      .zip(weights)
      .map(tup => tup._1 * tup._2)
      .sum / sumWeights

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
      weightedEnergy = weightedEnergy,
      weightedLevel4TravelTimeScaleFactor = weightedLevel4TravelTimeScale
    )
  }
}

object ODSkimmer extends LazyLogging {
  // cases
  case class ODSkimmerKey(hour: Int, mode: BeamMode, origin: String, destination: String) extends AbstractSkimmerKey {
    override def toCsv: String = hour + "," + mode + "," + origin + "," + destination
  }

  def fromCsv(
    row: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = row("hour").toInt,
        mode = BeamMode.fromString(row("mode").toLowerCase()).get,
        origin = row("origTaz"),
        destination = row("destTaz")
      ),
      ODSkimmerInternal(
        travelTimeInS = row("travelTimeInS").toDouble,
        generalizedTimeInS = row("generalizedTimeInS").toDouble,
        generalizedCost = row("generalizedCost").toDouble,
        distanceInM = row("distanceInM").toDouble,
        cost = row("cost").toDouble,
        energy = Option(row("energy")).map(_.toDouble).getOrElse(0.0),
        level4CavTravelTimeScalingFactor = row.get("level4CavTravelTimeScalingFactor").map(_.toDouble).getOrElse(1.0),
        observations = row("observations").toInt,
        iterations = row("iterations").toInt
      )
    )
  }

  case class ODSkimmerInternal(
    travelTimeInS: Double,
    generalizedTimeInS: Double,
    generalizedCost: Double,
    distanceInM: Double,
    cost: Double,
    energy: Double,
    level4CavTravelTimeScalingFactor: Double,
    observations: Int = 1,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {

    //NOTE: All times in seconds here
    def toSkimExternal: Skim =
      Skim(
        travelTimeInS.toInt,
        generalizedTimeInS,
        generalizedCost,
        distanceInM,
        cost,
        observations,
        energy,
        level4CavTravelTimeScalingFactor
      )

    def toSkimExternalForLevel4CAV: Skim =
      Skim(
        (travelTimeInS * level4CavTravelTimeScalingFactor).toInt,
        generalizedTimeInS,
        generalizedCost,
        distanceInM,
        cost,
        observations,
        energy,
        level4CavTravelTimeScalingFactor
      )

    override def toCsv: String =
      travelTimeInS + "," + generalizedTimeInS + "," + cost + "," + generalizedCost + "," + distanceInM + "," + energy + "," + level4CavTravelTimeScalingFactor + "," + observations + "," + iterations
  }

  case class Skim(
    time: Int = 0,
    generalizedTime: Double = 0,
    generalizedCost: Double = 0,
    distance: Double = 0,
    cost: Double = 0,
    count: Int = 0,
    energy: Double = 0,
    level4CavTravelTimeScalingFactor: Double = 1.0
  ) {

    def +(that: Skim): Skim =
      Skim(
        this.time + that.time,
        this.generalizedTime + that.generalizedTime,
        this.generalizedCost + that.generalizedCost,
        this.distance + that.distance,
        this.cost + that.cost,
        this.count + that.count,
        this.energy + that.energy,
        this.level4CavTravelTimeScalingFactor + that.level4CavTravelTimeScalingFactor
      )
  }

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
    weightedEnergy: Double,
    weightedLevel4TravelTimeScaleFactor: Double
  )
}
