package beam.router.skim

import beam.router.skim.ActivitySimPathType.{isWalkTransit, WLK_TRN_WLK}
import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import beam.router.skim.urbansim.ActivitySimOmxWriter
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
      val extension = if (config.activity_sim_skimmer.fileOutputFormat.equalsIgnoreCase("csv")) "csv.gz" else "omx"
      val filePath = event.getServices.getControlerIO
        .getIterationFilename(event.getServices.getIterationNumber, s"${skimFileBaseName}_current.$extension")
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
      .getOrElse(
        ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      )
    val currSkim =
      currIteration
        .map(_.asInstanceOf[ActivitySimSkimmerInternal])
        .getOrElse(
          ActivitySimSkimmerInternal(
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            observations = 0,
            iterations = 1
          )
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
      waitInitialInMinutes = aggregate(_.waitInitialInMinutes),
      waitAuxiliaryInMinutes = aggregate(_.waitAuxiliaryInMinutes),
      totalInVehicleTimeInMinutes = aggregate(_.totalInVehicleTimeInMinutes),
      driveTimeInMinutes = aggregate(_.driveTimeInMinutes),
      driveDistanceInMeters = aggregate(_.driveDistanceInMeters),
      ferryInVehicleTimeInMinutes = aggregate(_.ferryInVehicleTimeInMinutes),
      keyInVehicleTimeInMinutes = aggregate(_.keyInVehicleTimeInMinutes),
      transitBoardingsCount = aggregate(_.transitBoardingsCount),
      failedTrips =
        (prevSkim.failedTrips * prevSkim.iterations + currSkim.failedTrips * currSkim.iterations) / (prevSkim.iterations + currSkim.iterations),
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
      waitInitialInMinutes = aggregatedDoubleSkimValue(_.waitInitialInMinutes),
      waitAuxiliaryInMinutes = aggregatedDoubleSkimValue(_.waitAuxiliaryInMinutes),
      totalInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.totalInVehicleTimeInMinutes),
      driveTimeInMinutes = aggregatedDoubleSkimValue(_.driveTimeInMinutes),
      driveDistanceInMeters = aggregatedDoubleSkimValue(_.driveDistanceInMeters),
      ferryInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.ferryInVehicleTimeInMinutes),
      keyInVehicleTimeInMinutes = aggregatedDoubleSkimValue(_.keyInVehicleTimeInMinutes),
      transitBoardingsCount = aggregatedDoubleSkimValue(_.transitBoardingsCount),
      failedTrips = prevSkim.failedTrips + currSkim.failedTrips,
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

  private def writePresentedSkims(filePath: String): Unit = {
    case class ActivitySimKey(
      timeBin: ActivitySimTimeBin,
      pathType: ActivitySimPathType,
      origin: String,
      destination: String
    )

    val mappedSkimFilePath = filePath.lastIndexOf(".") match {
      case -1    => filePath + "_mapped" // No extension found, append "_mapped" at the end
      case index => filePath.substring(0, index) + "_mapped" + filePath.substring(index)
    }

    ProfilingUtils.timed("Writing skims that are created during simulation ", x => logger.info(x)) {

      val tempExcerptData = currentSkim
        .asInstanceOf[Map[ActivitySimSkimmerKey, ActivitySimSkimmerInternal]]
        .flatMap {
          case (key, value) if isWalkTransit(key.pathType) =>
            // The WLK_TRN_WLK skim is a catch-all for all transit trips, so rather than storing extra in the skims we
            // just duplicate the appropriate values when aggregating them up
            Map[ActivitySimSkimmerKey, ActivitySimSkimmerInternal](
              key                              -> value,
              key.copy(pathType = WLK_TRN_WLK) -> value
            )
          case (key, value) =>
            Map[ActivitySimSkimmerKey, ActivitySimSkimmerInternal](key -> value)
        }

      val maybeTazExcerptData = beamScenario.exchangeOutputGeoMap.map(exchangeGeoMap =>
        tempExcerptData
          .groupBy { case (key, _) =>
            val asTimeBin = ActivitySimTimeBin.toTimeBin(key.hour)
            (exchangeGeoMap.getMappedGeoId(key.origin), exchangeGeoMap.getMappedGeoId(key.destination)) match {
              case (Some(mappedOrigin), Some(mappedDestination)) =>
                Some(ActivitySimKey(asTimeBin, key.pathType, mappedOrigin, mappedDestination))
              case _ => None
            }
          }
          .flatMap {
            case (Some(key), skimMap) => Some(key -> skimMap)
            case _                    => None
          }
      )

      val excerptData = tempExcerptData.groupBy { case (key, _) =>
        ActivitySimKey(ActivitySimTimeBin.toTimeBin(key.hour), key.pathType, key.origin, key.destination)
      }

      val (defaultExcerptData, maybeMappedExcerptData) = maybeTazExcerptData match {
        case Some(tazExcerptData) if beamScenario.exchangeOutputGeoMap.get.getSize > beamScenario.tazTreeMap.getSize =>
          val cbgExcerptDataFiltered = excerptData
            .filter { case (key, _) =>
              beamConfig.beam.exchange.output.geo.get.beamModeFilter
                .contains(ActivitySimPathType.toBeamMode(key.pathType).value)
            }
          (tazExcerptData, Some(cbgExcerptDataFiltered))
        case Some(tazExcerptData) if beamScenario.exchangeOutputGeoMap.get.getSize < beamScenario.tazTreeMap.getSize =>
          val cbgExcerptData = excerptData
          val tazExcerptDataFiltered = tazExcerptData
            .filter { case (key, _) =>
              beamConfig.beam.exchange.output.geo.get.beamModeFilter
                .contains(ActivitySimPathType.toBeamMode(key.pathType).value)
            }
          (cbgExcerptData, Some(tazExcerptDataFiltered))
        case _ =>
          (excerptData, None)
      }

      val defaultSkim = defaultExcerptData.map { case (key, skimMap) =>
        weightedData(key.timeBin.entryName, key.origin, key.destination, key.pathType, skimMap.values.toList)
      }

      val maybeMappedSkim = maybeMappedExcerptData.map(_.map { case (key, skimMap) =>
        weightedData(key.timeBin.entryName, key.origin, key.destination, key.pathType, skimMap.values.toList)
      })

      val writeResult = if (config.activity_sim_skimmer.fileOutputFormat.trim.equalsIgnoreCase("csv")) {
        val csvWriter = new CsvWriter(filePath, ExcerptData.csvHeaderSeq)
        csvWriter.writeAllAndClose(defaultSkim.map(_.toCsvSeq))
        if (maybeMappedSkim.nonEmpty) {
          val csvWriterWithMapped = new CsvWriter(mappedSkimFilePath, ExcerptData.csvHeaderSeq)
          csvWriterWithMapped.writeAllAndClose(maybeMappedSkim.get.map(_.toCsvSeq))
        }
      } else {
        val geoUnits = beamScenario.exchangeOutputGeoMap.getOrElse(beamScenario.tazTreeMap).orderedTazIds
        ActivitySimOmxWriter.writeToOmx(filePath, defaultSkim.iterator, geoUnits)
        if (maybeMappedSkim.nonEmpty)
          ActivitySimOmxWriter.writeToOmx(mappedSkimFilePath, maybeMappedSkim.get.iterator, geoUnits)
      }

      writeResult match {
        case Failure(exception) =>
          logger.error(s"Cannot write skims to {}", filePath, exception)
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

  private def getExcerptDataOption(
    timeBin: ActivitySimTimeBin,
    origin: GeoUnit,
    destination: GeoUnit,
    pathType: ActivitySimPathType
  ): Option[ExcerptData] = {
    if (pathType == ActivitySimPathType.WALK && timeBin != ActivitySimTimeBin.EARLY_AM) {
      None
    } else {
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
  }

  private def weightedData(
    timePeriodString: String,
    originId: String,
    destinationId: String,
    pathType: ActivitySimPathType,
    individualSkims: List[ActivitySimSkimmerInternal]
  ) = {
    val weights = individualSkims.map(sk => sk.observations)
    val sumWeights = weights.sum

    def getWeightedSkimsValue(getValue: ActivitySimSkimmerInternal => Double): Double = {
      if (weights.sum == 0) { 0 }
      else {
        individualSkims
          .map(getValue)
          .zip(weights)
          .map { case (value, weight) => value * weight }
          .filter(!_.isNaN)
          .foldLeft(0d)(_ + _) / sumWeights
      }
    }

    val weightedDistance = getWeightedSkimsValue(_.distanceInMeters)
    val weightedTotalTime = getWeightedSkimsValue(_.travelTimeInMinutes)
    val weightedCostInDollars = getWeightedSkimsValue(_.cost)
    val weightedWalkAccessTime = getWeightedSkimsValue(_.walkAccessInMinutes)
    val weightedWalkEgressTime = getWeightedSkimsValue(_.walkEgressInMinutes)
    val weightedWalkAuxiliaryTime = getWeightedSkimsValue(_.walkAuxiliaryInMinutes)
    val weightedWaitInitialTime = getWeightedSkimsValue(_.waitInitialInMinutes)
    val weightedWaitTransferTime = getWeightedSkimsValue(_.waitAuxiliaryInMinutes)
    val weightedTotalInVehicleTime = getWeightedSkimsValue(_.totalInVehicleTimeInMinutes)
    val weightedDriveTime = getWeightedSkimsValue(_.driveTimeInMinutes)
    val weightedDriveDistance = getWeightedSkimsValue(_.driveDistanceInMeters)
    val weightedKeyInVehicleTime = getWeightedSkimsValue(_.keyInVehicleTimeInMinutes)
    val weightedFerryTime = getWeightedSkimsValue(_.ferryInVehicleTimeInMinutes)
    val weightedTransitBoardingsCount = getWeightedSkimsValue(_.transitBoardingsCount)
    val failedTrips = individualSkims.map(_.failedTrips).sum
    val completedTrips = individualSkims.map(_.observations).sum
    val debugText = individualSkims.map(_.debugText).filter(t => t != "").mkString("|")

    ExcerptData(
      timePeriodString = timePeriodString,
      pathType = pathType,
      originId = originId,
      destinationId = destinationId,
      weightedTotalTime = weightedTotalTime,
      weightedTotalInVehicleTime = weightedTotalInVehicleTime,
      weightedTotalFareInCents = weightedCostInDollars * 100,
      weightedDistance = weightedDistance,
      weightedWalkAccess = weightedWalkAccessTime,
      weightedWalkAuxiliary = weightedWalkAuxiliaryTime,
      weightedWalkEgress = weightedWalkEgressTime,
      weightedWaitInitial = weightedWaitInitialTime,
      weightedWaitTransfer = weightedWaitTransferTime,
      weightedDriveTimeInMinutes = weightedDriveTime,
      weightedDriveDistanceInMeters = weightedDriveDistance,
      weightedKeyInVehicleTimeInMinutes = weightedKeyInVehicleTime,
      weightedFerryInVehicleTimeInMinutes = weightedFerryTime,
      weightedTransitBoardingsCount = weightedTransitBoardingsCount,
      weightedCost = weightedCostInDollars,
      failedTrips = failedTrips,
      completedTrips = completedTrips,
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
      ExcerptData(
        timeBin.toString,
        pathType,
        origin.id,
        destination.id,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        ""
      )
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
    waitInitialInMinutes: Double,
    waitAuxiliaryInMinutes: Double,
    totalInVehicleTimeInMinutes: Double,
    driveTimeInMinutes: Double,
    driveDistanceInMeters: Double,
    ferryInVehicleTimeInMinutes: Double,
    keyInVehicleTimeInMinutes: Double,
    transitBoardingsCount: Double,
    failedTrips: Int,
    observations: Int,
    iterations: Int = 0,
    debugText: String = ""
  ) extends AbstractSkimmerInternal {

    override def toCsv: String =
      travelTimeInMinutes + "," + generalizedTimeInMinutes + "," + cost + "," + generalizedCost + "," +
      distanceInMeters + "," + energy + "," + failedTrips + "," + observations + "," + iterations
  }

  object ActivitySimSkimmerInternal {

    def empty: ActivitySimSkimmerInternal =
      ActivitySimSkimmerInternal(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  }

  case class ExcerptData(
    timePeriodString: String,
    pathType: ActivitySimPathType,
    originId: String,
    destinationId: String,
    weightedTotalTime: Double,
    weightedTotalInVehicleTime: Double,
    weightedTotalFareInCents: Double,
    weightedDistance: Double,
    weightedWalkAccess: Double,
    weightedWalkAuxiliary: Double,
    weightedWalkEgress: Double,
    weightedWaitInitial: Double,
    weightedWaitTransfer: Double,
    weightedDriveTimeInMinutes: Double,
    weightedDriveDistanceInMeters: Double,
    weightedKeyInVehicleTimeInMinutes: Double,
    weightedFerryInVehicleTimeInMinutes: Double,
    weightedTransitBoardingsCount: Double,
    weightedCost: Double,
    failedTrips: Int,
    completedTrips: Int,
    debugText: String = ""
  ) {

    def getValue(metric: ActivitySimMetric): Double = {
      metric match {
        case ActivitySimMetric.TOTIVT   => weightedTotalInVehicleTime
        case ActivitySimMetric.IVT      => weightedTotalInVehicleTime
        case ActivitySimMetric.FERRYIVT => weightedFerryInVehicleTimeInMinutes
        case ActivitySimMetric.FAR      => weightedTotalFareInCents
        case ActivitySimMetric.KEYIVT   => weightedKeyInVehicleTimeInMinutes
        case ActivitySimMetric.DTIM     => weightedDriveTimeInMinutes
        case ActivitySimMetric.BOARDS   => weightedTransitBoardingsCount
        case ActivitySimMetric.DDIST    => weightedDriveDistanceInMeters
        case ActivitySimMetric.WAUX     => weightedWalkAuxiliary
        case ActivitySimMetric.TIME     => weightedTotalTime
        case ActivitySimMetric.DIST     => weightedDistance
        case ActivitySimMetric.WEGR     => weightedWalkEgress
        case ActivitySimMetric.WACC     => weightedWalkAccess
        case ActivitySimMetric.IWAIT    => weightedWaitInitial
        case ActivitySimMetric.XWAIT    => weightedWaitTransfer
        case ActivitySimMetric.TRIPS    => completedTrips
        case ActivitySimMetric.FAILURES => failedTrips
        case _                          => Double.NaN
      }
    }

    def toCsvString: String = productIterator.mkString("", ",", "\n")

    def toCsvSeq: Seq[Any] = productIterator.toSeq
  }

  object ExcerptData {

    val supportedActivitySimMetric: Set[ActivitySimMetric] = Set(
      ActivitySimMetric.TOTIVT,
      ActivitySimMetric.IVT,
      ActivitySimMetric.FERRYIVT,
      ActivitySimMetric.FAR,
      ActivitySimMetric.KEYIVT,
      ActivitySimMetric.DTIM,
      ActivitySimMetric.BOARDS,
      ActivitySimMetric.DDIST,
      ActivitySimMetric.WAUX,
      ActivitySimMetric.TIME,
      ActivitySimMetric.DIST,
      ActivitySimMetric.WEGR,
      ActivitySimMetric.WACC,
      ActivitySimMetric.IWAIT,
      ActivitySimMetric.XWAIT,
      ActivitySimMetric.TRIPS,
      ActivitySimMetric.FAILURES
    )

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
      "IWAIT_minutes",
      "XWAIT_minutes",
      "DTIM_minutes",
      "DDIST_meters",
      "KEYIVT_minutes",
      "FERRYIVT_minutes",
      "BOARDS",
      "WeightedCost",
      "failedTrips",
      "completedTrips",
      "DEBUG_TEXT"
    )

    val csvHeader: String = csvHeaderSeq.mkString(",")
  }
}
