package beam.router.skim

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationEndsEvent

import java.io.BufferedWriter
import scala.util.control.NonFatal

class ActivitySimSkimmer @Inject()(matsimServices: MatsimServices, beamScenario: BeamScenario, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {

  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim
  import ActivitySimSkimmer._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = ActivitySimSkims(beamConfig, beamScenario)

  override protected val skimName: String = config.activity_sim_skimmer.name
  override protected val skimFileBaseName: String = config.activity_sim_skimmer.fileBaseName
  override protected val skimFileHeader: String = ExcerptData.csvHeader

  override def writeToDisk(event: IterationEndsEvent): Unit = ???

  override def fromCsv(
    row: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) = ??? // ActivitySimSkimmer.fromCsv(row)

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {
    val prevSkim = prevIteration
      .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      .getOrElse(ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observations = 0, iterations = 0))
    val currSkim =
      currIteration
        .map(_.asInstanceOf[ActivitySimSkimmerInternal])
        .getOrElse(ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observations = 0, iterations = 1))

    def aggregatedDoubleSkimValue(getValue: ActivitySimSkimmerInternal => Double): Double = {
      (getValue(prevSkim) * prevSkim.iterations + getValue(currSkim) * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations)
    }
    def aggregatedIntSkimValue(getValue: ActivitySimSkimmerInternal => Int): Int = {
      (getValue(prevSkim) * prevSkim.iterations + getValue(currSkim) * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations)
    }

    ActivitySimSkimmerInternal(
      travelTimeInMinutes = aggregatedDoubleSkimValue(_.travelTimeInMinutes),
      generalizedTimeInMinutes = aggregatedDoubleSkimValue(_.generalizedTimeInMinutes),
      generalizedCost = aggregatedDoubleSkimValue(_.generalizedCost),
      distanceInM = aggregatedDoubleSkimValue(_.distanceInM),
      cost = aggregatedDoubleSkimValue(_.cost),
      energy = aggregatedDoubleSkimValue(_.energy),
      walkAccess = aggregatedDoubleSkimValue(_.walkAccess),
      walkEgress = aggregatedDoubleSkimValue(_.walkEgress),
      walkAuxiliary = aggregatedDoubleSkimValue(_.walkAuxiliary),
      totalInVehicleTime = aggregatedDoubleSkimValue(_.totalInVehicleTime),
      observations = aggregatedIntSkimValue(_.observations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      .getOrElse(ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observations = 0, iterations = 0))
    val currSkim = currObservation.asInstanceOf[ActivitySimSkimmerInternal]

    def aggregatedDoubleSkimValue(getValue: ActivitySimSkimmerInternal => Double): Double = {
      (getValue(prevSkim) * prevSkim.observations + getValue(currSkim) * currSkim.observations) / (prevSkim.observations + currSkim.observations)
    }

    ActivitySimSkimmerInternal(
      travelTimeInMinutes = aggregatedDoubleSkimValue(_.travelTimeInMinutes),
      generalizedTimeInMinutes = aggregatedDoubleSkimValue(_.generalizedTimeInMinutes),
      generalizedCost = aggregatedDoubleSkimValue(_.generalizedCost),
      distanceInM = aggregatedDoubleSkimValue(_.distanceInM),
      cost = aggregatedDoubleSkimValue(_.cost),
      energy = aggregatedDoubleSkimValue(_.energy),
      walkAccess = aggregatedDoubleSkimValue(_.walkAccess),
      walkEgress = aggregatedDoubleSkimValue(_.walkEgress),
      walkAuxiliary = aggregatedDoubleSkimValue(_.walkAuxiliary),
      totalInVehicleTime = aggregatedDoubleSkimValue(_.totalInVehicleTime),
      observations = prevSkim.observations + currSkim.observations,
      iterations = matsimServices.getIterationNumber + 1
    )
  }

  protected def writeSkimsForTimePeriods(origins: Seq[GeoUnit], destinations: Seq[GeoUnit], filePath: String): Unit = {
    //  from activity sim documentation:
    //    EA - early AM, 3 am to 6 am
    //    AM - peak period, 6 am to 10 am,
    //    MD - midday period, 10 am to 3 pm,
    //    PM - peak period, 3 pm to 7 pm,
    //    EV - evening, 7 pm to 3 am the next day

    val EAHours = (3 until 6).toList
    val AMHours = (6 until 10).toList
    val MDHours = (10 until 15).toList
    val PMHours = (15 until 19).toList
    val EVHours = (0 until 3).toList ++ (19 until 24).toList

    val pathTypes = ActivitySimPathType.allPathTypes
    val dummyId = Id.create(
      beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
      classOf[BeamVehicleType]
    )
    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(ExcerptData.csvHeader)
      writer.write("\n")

      ProfilingUtils.timed("Writing skims for time periods for all pathTypes", x => logger.info(x)) {
        pathTypes.foreach { pathType =>
          origins.foreach { origin =>
            destinations.foreach { destination =>
              def getExcerptDataForTimePeriod(timePeriodName: String, timePeriodHours: List[Int]): ExcerptData = {
                getExcerptData(
                  timePeriodName,
                  timePeriodHours,
                  origin,
                  destination,
                  pathType,
                  dummyId
                )
              }

              val ea = getExcerptDataForTimePeriod("EA", EAHours)
              val am = getExcerptDataForTimePeriod("AM", AMHours)
              val md = getExcerptDataForTimePeriod("MD", MDHours)
              val pm = getExcerptDataForTimePeriod("PM", PMHours)
              val ev = getExcerptDataForTimePeriod("EV", EVHours)

              List(ea, am, md, pm, ev).foreach { excerptData: ExcerptData =>
                writer.write(excerptData.toCsvString)
              }
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
    origin: GeoUnit,
    destination: GeoUnit,
    pathType: ActivitySimPathType,
    dummyId: Id[BeamVehicleType]
  ): ExcerptData = {
    import scala.language.implicitConversions
    val individualSkims = hoursIncluded.map { timeBin =>
      currentSkim
        .get(ActivitySimSkimmerKey(timeBin, pathType, origin.id, destination.id))
        .map(_.asInstanceOf[ActivitySimSkimmerInternal])
        .getOrElse {
          val adjustedDestCoord = if (origin.equals(destination)) {
            new Coord(
              origin.center.getX,
              origin.center.getY + Math.sqrt(origin.areaInSquareMeters) / 2.0
            )
          } else {
            destination.center
          }
          readOnlySkim
            .asInstanceOf[ActivitySimSkims]
            .getSkimDefaultValue(
              pathType,
              origin.center,
              adjustedDestCoord,
              timeBin * 3600,
              dummyId,
              beamScenario
            )
        }
    }
    val weights = individualSkims.map(sk => Math.max(sk.observations, 1).toDouble)
    val sumWeights = weights.sum

    def getWeightedSkimsValue(getValue: ActivitySimSkimmerInternal => Double): Double =
      individualSkims.map(getValue).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights

    val weightedDistance = getWeightedSkimsValue(_.distanceInM)
    val weightedGeneralizedTime = getWeightedSkimsValue(_.generalizedTimeInMinutes)
    val weightedGeneralizedCost = getWeightedSkimsValue(_.generalizedCost)
    val weightedWalkAccessTime = getWeightedSkimsValue(_.walkAccess)
    val weightedWalkEgressTime = getWeightedSkimsValue(_.walkEgress)
    val weightedWalkAuxiliaryTime = getWeightedSkimsValue(_.walkAuxiliary)
    val weightedTotalInVehicleTime = getWeightedSkimsValue(_.totalInVehicleTime)

    ExcerptData(
      timePeriodString = timePeriodString,
      pathType = pathType,
      originId = origin.id,
      destinationId = destination.id,
      weightedGeneralizedTime = weightedGeneralizedTime,
      weightedGeneralizedCost = weightedGeneralizedCost,
      weightedDistance = weightedDistance,
      weightedWalkAccess = weightedWalkAccessTime,
      weightedWalkEgress = weightedWalkEgressTime,
      weightedWalkAuxiliary = weightedWalkAuxiliaryTime,
      weightedTotalInVehicleTime = weightedTotalInVehicleTime
    )
  }
}

object ActivitySimSkimmer extends LazyLogging {
  case class ActivitySimSkimmerKey(hour: Int, pathType: ActivitySimPathType, origin: String, destination: String)
      extends AbstractSkimmerKey {
    override def toCsv: String = hour + "," + pathType + "," + origin + "," + destination
  }

  case class ActivitySimSkimmerInternal(
    travelTimeInMinutes: Double,
    generalizedTimeInMinutes: Double,
    generalizedCost: Double,
    distanceInM: Double,
    cost: Double,
    energy: Double,
    walkAccess: Double,
    walkEgress: Double,
    walkAuxiliary: Double,
    totalInVehicleTime: Double,
    observations: Int = 1,
    iterations: Int = 0
  ) extends AbstractSkimmerInternal {

    override def toCsv: String =
      travelTimeInMinutes + "," + generalizedTimeInMinutes + "," + cost + "," + generalizedCost + "," +
      distanceInM + "," + energy + "," + observations + "," + iterations
  }

  case class ExcerptData(
    timePeriodString: String,
    pathType: ActivitySimPathType,
    originId: String,
    destinationId: String,
    weightedGeneralizedTime: Double,
    weightedGeneralizedCost: Double,
    weightedDistance: Double,
    weightedWalkAccess: Double,
    weightedWalkEgress: Double,
    weightedWalkAuxiliary: Double,
    weightedTotalInVehicleTime: Double,
  ) {

    def toCsvString: String = {
      s"$timePeriodString,$pathType,$originId,$destinationId,$weightedGeneralizedTime,$weightedTotalInVehicleTime,$weightedGeneralizedCost,$weightedDistance,$weightedWalkAccess,$weightedWalkAuxiliary,$weightedWalkEgress\n"
    }
  }

  object ExcerptData {

    val csvHeader =
      "timePeriod,pathType,origin,destination,TIME,TOTIVT_IVT,VTOLL_FAR,DIST,WACC,WAUX,WEGR"
  }
}
