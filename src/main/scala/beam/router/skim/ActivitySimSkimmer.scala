package beam.router.skim

import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
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
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.AS_SKIMMER
  override protected val skimFileBaseName: String = config.activity_sim_skimmer.fileBaseName
  override protected val skimFileHeader: String = ExcerptData.csvHeader

  override def writeToDisk(event: IterationEndsEvent): Unit =
    throw new NotImplementedError("This functionality was not expected to be used.")

  override def fromCsv(
    row: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) =
    throw new NotImplementedError("This functionality was not expected to be used.")

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = throw new NotImplementedError("This functionality was not expected to be used.")

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      .getOrElse(ActivitySimSkimmerInternal.empty)
    val currSkim = currObservation.asInstanceOf[ActivitySimSkimmerInternal]

    def aggregatedDoubleSkimValue(getValue: ActivitySimSkimmerInternal => Double): Double = {
      (getValue(prevSkim) * prevSkim.observations + getValue(currSkim) * currSkim.observations) / (prevSkim.observations + currSkim.observations)
    }

    ActivitySimSkimmerInternal(
      travelTimeInMinutes = aggregatedDoubleSkimValue(_.travelTimeInMinutes),
      generalizedTimeInMinutes = aggregatedDoubleSkimValue(_.generalizedTimeInMinutes),
      generalizedCost = aggregatedDoubleSkimValue(_.generalizedCost),
      distanceInMeters = aggregatedDoubleSkimValue(_.distanceInMeters),
      cost = aggregatedDoubleSkimValue(_.cost),
      energy = aggregatedDoubleSkimValue(_.energy),
      walkAccessInMinutes = aggregatedDoubleSkimValue(_.walkAccessInMinutes),
      walkEgressInMinutes = aggregatedDoubleSkimValue(_.walkEgressInMinutes),
      walkAuxiliaryInMinutes = aggregatedDoubleSkimValue(_.walkAuxiliaryInMinutes),
      totalInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.totalInVehicleTimeInMinutes),
      driveTimeInMinutes = aggregatedDoubleSkimValue(_.driveTimeInMinutes),
      driveDistanceInMeters = aggregatedDoubleSkimValue(_.driveDistanceInMeters),
      ferryInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.ferryInVehicleTimeInMinutes),
      lightRailInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.lightRailInVehicleTimeInMinutes),
      transitBoardingsCount = aggregatedDoubleSkimValue(_.transitBoardingsCount),
      observations = prevSkim.observations + currSkim.observations,
      iterations = matsimServices.getIterationNumber + 1,
      debugText = Seq(prevSkim.debugText, currSkim.debugText).mkString("|")
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
                  pathType
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
    pathType: ActivitySimPathType
  ): ExcerptData = {
    import scala.language.implicitConversions
    val individualSkims = {
      val skimsForHours = hoursIncluded.flatMap { timeBin =>
        readOnlySkim
          .getCurrentSkimValue(ActivitySimSkimmerKey(timeBin, pathType, origin.id, destination.id))
          .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      }
      if (skimsForHours.nonEmpty) { skimsForHours } else {
        List(ActivitySimSkimmerInternal.empty)
      }
    }

    val weights = individualSkims.map(sk => sk.observations)
    val sumWeights = if (weights.sum == 0) { 1 } else { weights.sum }

    def getWeightedSkimsValue(getValue: ActivitySimSkimmerInternal => Double): Double =
      individualSkims.map(getValue).zip(weights).map(tup => tup._1 * tup._2).sum / sumWeights

    val weightedDistance = getWeightedSkimsValue(_.distanceInMeters)
    val weightedGeneralizedTime = getWeightedSkimsValue(_.generalizedTimeInMinutes)
    val weightedGeneralizedCost = getWeightedSkimsValue(_.generalizedCost)
    val weightedCost = getWeightedSkimsValue(_.cost)
    val weightedWalkAccessTime = getWeightedSkimsValue(_.walkAccessInMinutes)
    val weightedWalkEgressTime = getWeightedSkimsValue(_.walkEgressInMinutes)
    val weightedWalkAuxiliaryTime = getWeightedSkimsValue(_.walkAuxiliaryInMinutes)
    val weightedTotalInVehicleTime = getWeightedSkimsValue(_.totalInVehicleTimeInMinutes)
    val weightedDriveTime = getWeightedSkimsValue(_.driveTimeInMinutes)
    val weightedDriveDistance = getWeightedSkimsValue(_.driveDistanceInMeters)
    val weightedLightRailTime = getWeightedSkimsValue(_.lightRailInVehicleTimeInMinutes)
    val weightedFerryTime = getWeightedSkimsValue(_.ferryInVehicleTimeInMinutes)
    val weightedTransitBoardingsCount = getWeightedSkimsValue(_.transitBoardingsCount)
    val debugText = individualSkims.map(_.debugText).filter(t => t != "").mkString("|")

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
      weightedTotalInVehicleTime = weightedTotalInVehicleTime,
      weightedDriveTimeInMinutes = weightedDriveTime,
      weightedDriveDistanceInMeters = weightedDriveDistance,
      weightedFerryInVehicleTimeInMinutes = weightedFerryTime,
      weightedLightRailInVehicleTimeInMinutes = weightedLightRailTime,
      weightedTransitBoardingsCount = weightedTransitBoardingsCount,
      weightedCost = weightedCost,
      debugText = debugText
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
    distanceInMeters: Double,
    cost: Double,
    energy: Double,
    walkAccessInMinutes: Double,
    walkEgressInMinutes: Double,
    walkAuxiliaryInMinutes: Double,
    totalInVehicleTimeInMinutes: Double,
    driveTimeInMinutes: Double,
    driveDistanceInMeters: Double,
    ferryInVehicleTimeInMinutes: Double,
    lightRailInVehicleTimeInMinutes: Double,
    transitBoardingsCount: Double,
    observations: Int = 1,
    iterations: Int = 0,
    debugText: String = "",
  ) extends AbstractSkimmerInternal {

    override def toCsv: String =
      travelTimeInMinutes + "," + generalizedTimeInMinutes + "," + cost + "," + generalizedCost + "," +
      distanceInMeters + "," + energy + "," + observations + "," + iterations
  }

  object ActivitySimSkimmerInternal {
    def empty: ActivitySimSkimmerInternal = ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
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
    weightedDriveTimeInMinutes: Double,
    weightedDriveDistanceInMeters: Double,
    weightedFerryInVehicleTimeInMinutes: Double,
    weightedLightRailInVehicleTimeInMinutes: Double,
    weightedTransitBoardingsCount: Double,
    weightedCost: Double,
    debugText: String = "",
  ) {

    def toCsvString: String = {
      s"$timePeriodString,$pathType,$originId," +
      s"$destinationId,$weightedGeneralizedTime,$weightedTotalInVehicleTime," +
      s"$weightedGeneralizedCost,$weightedDistance,$weightedWalkAccess," +
      s"$weightedWalkAuxiliary,$weightedWalkEgress,$weightedDriveTimeInMinutes," +
      s"$weightedDriveDistanceInMeters,$weightedLightRailInVehicleTimeInMinutes,$weightedFerryInVehicleTimeInMinutes," +
      s"$weightedTransitBoardingsCount,$weightedCost,$debugText\n"
    }
  }

  object ExcerptData {

    val csvHeader: String =
    "timePeriod,pathType,origin," +
    "destination,TIME_minutes,TOTIVT_IVT_minutes," +
    "VTOLL_FAR,DIST_meters,WACC_minutes," +
    "WAUX_minutes,WEGR_minutes,DTIM_minutes," +
    "DDIST_meters,KEYIVT_minutes,FERRYIVT_minutes," +
    "BOARDS,WeightedCost,DEBUG_TEXT"
  }
}
