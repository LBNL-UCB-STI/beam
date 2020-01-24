package beam.router.skim
import java.io.BufferedWriter

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.immutable
import scala.util.control.NonFatal

class ODSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends AbstractSkimmer(beamServices, config) {
  import ODSkimmer._
  import beamServices._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = ODSkims(beamServices)

  override protected val skimName: String = config.origin_destination_skimmer.name
  override protected val skimFileBaseName: String = config.origin_destination_skimmer.fileBaseName
  override protected val skimFileHeader: String =
    "hour,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,energy,numObservations,numIteration"

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    super.writeToDisk(event)
    if (config.origin_destination_skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval > 0 && event.getIteration % config.origin_destination_skimmer.writeAllModeSkimsForPeakNonPeakPeriodsInterval == 0) {
      ProfilingUtils.timed(s"writeAllModeSkimsForPeakNonPeakPeriods on iteration ${event.getIteration}", logger.info(_)) {
        writeAllModeSkimsForPeakNonPeakPeriods(event)
      }
    }
    if (config.origin_destination_skimmer.writeFullSkimsInterval > 0 && event.getIteration % config.origin_destination_skimmer.writeFullSkimsInterval == 0) {
      ProfilingUtils.timed(s"writeFullSkims on iteration ${event.getIteration}", logger.info(_)) {
        writeFullSkims(event)
      }
    }
  }

  override def fromCsv(
    row: immutable.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = row("hour").toInt,
        mode = BeamMode.fromString(row("mode").toLowerCase()).get,
        originTaz = Id.create(row("origTaz"), classOf[TAZ]),
        destinationTaz = Id.create(row("destTaz"), classOf[TAZ])
      ),
      ODSkimmerInternal(
        travelTimeInS = row("travelTimeInS").toDouble,
        generalizedTimeInS = row("generalizedTimeInS").toDouble,
        generalizedCost = row("generalizedCost").toDouble,
        distanceInM = row("distanceInM").toDouble,
        cost = row("cost").toDouble,
        energy = Option(row("energy")).map(_.toDouble).getOrElse(0.0),
        numObservations = row("numObservations").toInt,
        numIteration = row("numIteration").toInt
      )
    )
  }

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[ODSkimmerInternal])
      .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, numObservations = 0, numIteration = 0))
    val currSkim =
      currIteration
        .map(_.asInstanceOf[ODSkimmerInternal])
        .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, numObservations = 0, numIteration = 1))
    ODSkimmerInternal(
      travelTimeInS = (prevSkim.travelTimeInS * prevSkim.numIteration + currSkim.travelTimeInS * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      generalizedTimeInS = (prevSkim.generalizedTimeInS * prevSkim.numIteration + currSkim.generalizedTimeInS * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      generalizedCost = (prevSkim.generalizedCost * prevSkim.numIteration + currSkim.generalizedCost * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      distanceInM = (prevSkim.distanceInM * prevSkim.numIteration + currSkim.distanceInM * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      cost = (prevSkim.cost * prevSkim.numIteration + currSkim.cost * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      energy = (prevSkim.energy * prevSkim.numIteration + currSkim.energy * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      numObservations = (prevSkim.numObservations * prevSkim.numIteration + currSkim.numObservations * currSkim.numIteration) / (prevSkim.numIteration + currSkim.numIteration),
      numIteration = prevSkim.numIteration + currSkim.numIteration
    )
  }

  override protected def aggregateWithinAnIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[ODSkimmerInternal])
      .getOrElse(ODSkimmerInternal(0, 0, 0, 0, 0, 0, numObservations = 0, numIteration = 0))
    val currSkim = currObservation.asInstanceOf[ODSkimmerInternal]
    ODSkimmerInternal(
      travelTimeInS = (prevSkim.travelTimeInS * prevSkim.numObservations + currSkim.travelTimeInS * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      generalizedTimeInS = (prevSkim.generalizedTimeInS * prevSkim.numObservations + currSkim.generalizedTimeInS * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      generalizedCost = (prevSkim.generalizedCost * prevSkim.numObservations + currSkim.generalizedCost * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      distanceInM = (prevSkim.distanceInM * prevSkim.numObservations + currSkim.distanceInM * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      cost = (prevSkim.cost * prevSkim.numObservations + currSkim.cost * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      energy = (prevSkim.energy * prevSkim.numObservations + currSkim.energy * currSkim.numObservations) / (prevSkim.numObservations + currSkim.numObservations),
      numObservations = prevSkim.numObservations + currSkim.numObservations,
      numIteration = beamServices.matsimServices.getIterationNumber + 1
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
      "period,mode,origTaz,destTaz,travelTimeInS,generalizedTimeInS,cost,generalizedCost,distanceInM,numObservations,energy"
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
        logger.error(s"Could not write skim in '${filePath}': ${ex.getMessage}", ex)
    } finally {
      if (null != writer)
        writer.close()
    }
  }

  private def writeFullSkims(event: IterationEndsEvent): Unit = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      skimFileBaseName + "Full.csv.gz"
    )
    val uniqueModes = currentSkim.map(keyVal => keyVal.asInstanceOf[ODSkimmerKey].mode).toList.distinct
    val uniqueTimeBins = 0 to 23

    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )

    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(skimFileHeader + "\n")

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
                        readOnlySkim
                          .asInstanceOf[ODSkims]
                          .getSkimDefaultValue(
                            mode,
                            origin.coord,
                            newDestCoord,
                            timeBin * 3600,
                            dummyId
                          )
                      } else {
                        readOnlySkim
                          .asInstanceOf[ODSkims]
                          .getSkimDefaultValue(
                            mode,
                            origin.coord,
                            destination.coord,
                            timeBin * 3600,
                            dummyId
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
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write skim in '${filePath}': ${ex.getMessage}", ex)
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
          readOnlySkim
            .asInstanceOf[ODSkims]
            .getSkimDefaultValue(
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
}

object ODSkimmer extends LazyLogging {
  // cases
  case class ODSkimmerKey(hour: Int, mode: BeamMode, originTaz: Id[TAZ], destinationTaz: Id[TAZ])
      extends AbstractSkimmerKey {
    override def toCsv: String = hour + "," + mode + "," + originTaz + "," + destinationTaz
  }
  case class ODSkimmerInternal(
    travelTimeInS: Double,
    generalizedTimeInS: Double,
    generalizedCost: Double,
    distanceInM: Double,
    cost: Double,
    energy: Double,
    numObservations: Int = 1,
    numIteration: Int = 0
  ) extends AbstractSkimmerInternal {

    //NOTE: All times in seconds here
    def toSkimExternal: Skim =
      Skim(travelTimeInS.toInt, generalizedTimeInS, generalizedCost, distanceInM, cost, numObservations, energy)
    override def toCsv: String =
      travelTimeInS + "," + generalizedTimeInS + "," + cost + "," + generalizedCost + "," + distanceInM + "," + energy + "," + numObservations + "," + numIteration
  }

  case class Skim(
    time: Int = 0,
    generalizedTime: Double = 0,
    generalizedCost: Double = 0,
    distance: Double = 0,
    cost: Double = 0,
    count: Int = 0,
    energy: Double = 0
  ) {

    def +(that: Skim): Skim =
      Skim(
        this.time + that.time,
        this.generalizedTime + that.generalizedTime,
        this.generalizedCost + that.generalizedCost,
        this.distance + that.distance,
        this.cost + that.cost,
        this.count + that.count,
        this.energy + that.energy
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
    weightedEnergy: Double
  )
}
