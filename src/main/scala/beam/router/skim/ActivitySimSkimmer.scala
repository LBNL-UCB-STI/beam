package beam.router.skim

import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationEndsEvent

import java.io.BufferedWriter
import scala.util.Failure
import scala.util.control.NonFatal

class ActivitySimSkimmer @Inject() (matsimServices: MatsimServices, beamScenario: BeamScenario, beamConfig: BeamConfig)
    extends AbstractSkimmer(beamConfig, matsimServices.getControlerIO) {

  private val config: BeamConfig.Beam.Router.Skim = beamConfig.beam.router.skim
  import ActivitySimSkimmer._

  override lazy val readOnlySkim: AbstractSkimmerReadOnly = ActivitySimSkims(beamConfig, beamScenario)

  override protected val skimName: String = config.activity_sim_skimmer.name
  override protected val skimType: Skims.SkimType.Value = Skims.SkimType.AS_SKIMMER
  override protected val skimFileBaseName: String = config.activity_sim_skimmer.fileBaseName
  override protected val skimFileHeader: String = ExcerptData.csvHeader

  override def writeToDisk(event: IterationEndsEvent): Unit =
    if (config.writeSkimsInterval > 0 && event.getIteration % config.writeSkimsInterval == 0) {
      val filePath = event.getServices.getControlerIO
        .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + "_current.csv.gz")
      writePresentedSkims(filePath)
    }

  override def fromCsv(
    row: scala.collection.Map[String, String]
  ): (AbstractSkimmerKey, AbstractSkimmerInternal) =
    throw new NotImplementedError("This functionality was not expected to be used.")

  override protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal = {

    val prevSkim = prevIteration
      .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      .getOrElse(ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observations = 0))
    val currSkim =
      currIteration
        .map(_.asInstanceOf[ActivitySimSkimmerInternal])
        .getOrElse(
          ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, observations = 0, iterations = 1)
        )

    def aggregate(getValue: ActivitySimSkimmerInternal => Double): Double = {
      val prevValue: Double = getValue(prevSkim)
      val curValue: Double = getValue(currSkim)
      (prevValue * prevSkim.iterations + curValue * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations)
    }

    ActivitySimSkimmerInternal(
      travelTimeInMinutes = aggregate(_.travelTimeInMinutes),
      generalizedTimeInMinutes = aggregate(_.generalizedTimeInMinutes),
      generalizedCost = aggregate(_.generalizedCost),
      distanceInMeters = aggregate(_.distanceInMeters),
      cost = aggregate(_.cost),
      energy = aggregate(_.energy),
      walkAccessInMinutes = aggregate(_.walkAccessInMinutes),
      walkEgressInMinutes = aggregate(_.walkEgressInMinutes),
      walkAuxiliaryInMinutes = aggregate(_.walkAuxiliaryInMinutes),
      totalInVehicleTimeInMinutes = aggregate(_.totalInVehicleTimeInMinutes),
      driveTimeInMinutes = aggregate(_.driveTimeInMinutes),
      driveDistanceInMeters = aggregate(_.driveDistanceInMeters),
      ferryInVehicleTimeInMinutes = aggregate(_.ferryInVehicleTimeInMinutes),
      lightRailInVehicleTimeInMinutes = aggregate(_.lightRailInVehicleTimeInMinutes),
      transitBoardingsCount = aggregate(_.transitBoardingsCount),
      observations =
        (prevSkim.observations * prevSkim.iterations + currSkim.observations * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
      iterations = prevSkim.iterations + currSkim.iterations
    )
  }

  override protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal = {
    val prevSkim = prevObservation
      .map(_.asInstanceOf[ActivitySimSkimmerInternal])
      .getOrElse(ActivitySimSkimmerInternal.empty)
    val currSkim = currObservation.asInstanceOf[ActivitySimSkimmerInternal]

    def aggregatedDoubleSkimValue(getValue: ActivitySimSkimmerInternal => Double): Double = {
      (getValue(prevSkim) * prevSkim.observations + getValue(
        currSkim
      ) * currSkim.observations) / (prevSkim.observations + currSkim.observations)
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

  protected def writeSkimRow(
    writer: BufferedWriter,
    origin: GeoUnit,
    destination: GeoUnit,
    pathType: ActivitySimPathType
  ): Unit = {
    ActivitySimTimeBin.values.foreach { timeBin =>
      val excerptData = getExcerptData(timeBin, origin, destination, pathType)
      writer.write(excerptData.toCsvString)
    }
  }

  protected def writeSkimsForTimePeriods(origins: Seq[GeoUnit], destinations: Seq[GeoUnit], filePath: String): Unit = {

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
              writeSkimRow(writer, origin, destination, pathType)
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

  def writePresentedSkims(filePath: String): Unit = {
    case class ActivitySimKey(
      timeBin: ActivitySimTimeBin,
      pathType: ActivitySimPathType,
      origin: String,
      destination: String
    )
    ProfilingUtils.timed("Writing skims that are created during simulation ", x => logger.info(x)) {
      val excerptData = currentSkim
        .asInstanceOf[Map[ActivitySimSkimmerKey, ActivitySimSkimmerInternal]]
        .groupBy { case (key, _) =>
          val asTimeBin = ActivitySimTimeBin.toTimeBin(key.hour)
          ActivitySimKey(asTimeBin, key.pathType, key.origin, key.destination)
        }
        .map { case (key, skimMap) =>
          weightedData(key.timeBin.entryName, key.origin, key.destination, key.pathType, skimMap.values.toList)
        }

      val csvWriter = new CsvWriter(filePath, ExcerptData.csvHeaderSeq)
      csvWriter.writeAllAndClose(excerptData.map(_.toCsvSeq)) match {
        case Failure(exception) =>
          logger.error(s"Cannot write to $filePath", exception)
        case _ =>
      }
    }
  }

  def getExcerptDataForOD(
    origin: GeoUnit,
    destination: GeoUnit
  ): Seq[ExcerptData] = {
    ActivitySimPathType.allPathTypes.flatMap { pathType =>
      ActivitySimTimeBin.values.flatMap(timeBin => getExcerptDataOption(timeBin, origin, destination, pathType))
    }
  }

  def getExcerptDataOption(
    timeBin: ActivitySimTimeBin,
    origin: GeoUnit,
    destination: GeoUnit,
    pathType: ActivitySimPathType
  ): Option[ExcerptData] = {
    val individualSkims = timeBin.hours.flatMap { hour =>
      getCurrentSkimValue(ActivitySimSkimmerKey(hour, pathType, origin.id, destination.id))
        .map(_.asInstanceOf[ActivitySimSkimmerInternal])
    }
    if (individualSkims.isEmpty) {
      None
    } else {
      Some(weightedData(timeBin.toString, origin.id, destination.id, pathType, individualSkims))
    }
  }

  private def weightedData(
    timePeriodString: String,
    originId: String,
    destinationId: String,
    pathType: ActivitySimPathType,
    individualSkims: List[ActivitySimSkimmerInternal]
  ) = {
    val weights = individualSkims.map(sk => sk.observations)
    val sumWeights = if (weights.sum == 0) 1 else weights.sum

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
      originId = originId,
      destinationId = destinationId,
      weightedGeneralizedTime = weightedGeneralizedTime,
      weightedTotalInVehicleTime = weightedTotalInVehicleTime,
      weightedGeneralizedCost = weightedGeneralizedCost,
      weightedDistance = weightedDistance,
      weightedWalkAccess = weightedWalkAccessTime,
      weightedWalkAuxiliary = weightedWalkAuxiliaryTime,
      weightedWalkEgress = weightedWalkEgressTime,
      weightedDriveTimeInMinutes = weightedDriveTime,
      weightedDriveDistanceInMeters = weightedDriveDistance,
      weightedLightRailInVehicleTimeInMinutes = weightedLightRailTime,
      weightedFerryInVehicleTimeInMinutes = weightedFerryTime,
      weightedTransitBoardingsCount = weightedTransitBoardingsCount,
      weightedCost = weightedCost,
      debugText = debugText
    )
  }

  def getExcerptData(
    timeBin: ActivitySimTimeBin,
    origin: GeoUnit,
    destination: GeoUnit,
    pathType: ActivitySimPathType
  ): ExcerptData = {
    getExcerptDataOption(timeBin, origin, destination, pathType).getOrElse(
      ExcerptData(timeBin.toString, pathType, origin.id, destination.id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "")
    )
  }
}

object ActivitySimSkimmer extends LazyLogging {
  case class ActivitySimSkimmerODKey(origin: String, destination: String)
  case class ActivitySimSkimmerPathHourKey(pathType: ActivitySimPathType, hour: Int)

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
    debugText: String = ""
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
    weightedTotalInVehicleTime: Double,
    weightedGeneralizedCost: Double,
    weightedDistance: Double,
    weightedWalkAccess: Double,
    weightedWalkAuxiliary: Double,
    weightedWalkEgress: Double,
    weightedDriveTimeInMinutes: Double,
    weightedDriveDistanceInMeters: Double,
    weightedLightRailInVehicleTimeInMinutes: Double,
    weightedFerryInVehicleTimeInMinutes: Double,
    weightedTransitBoardingsCount: Double,
    weightedCost: Double,
    debugText: String = ""
  ) {

    def toCsvString: String = productIterator.mkString("", ",", "\n")

    def toCsvSeq: Seq[Any] = productIterator.toSeq
  }

  object ExcerptData {

    val csvHeaderSeq: Seq[String] = Seq(
      "timePeriod",
      "pathType",
      "origin",
      "destination",
      "TIME_minutes",
      "TOTIVT_IVT_minutes",
      "VTOLL_FAR",
      "DIST_meters",
      "WACC_minutes",
      "WAUX_minutes",
      "WEGR_minutes",
      "DTIM_minutes",
      "DDIST_meters",
      "KEYIVT_minutes",
      "FERRYIVT_minutes",
      "BOARDS",
      "WeightedCost",
      "DEBUG_TEXT"
    )

    val csvHeader: String = csvHeaderSeq.mkString(",")
  }
}
