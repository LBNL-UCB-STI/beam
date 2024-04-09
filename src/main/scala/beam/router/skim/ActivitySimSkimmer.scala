package beam.router.skim

import beam.router.skim.ActivitySimPathType.{isWalkTransit, WLK_TRN_WLK}
import beam.router.skim.core.{AbstractSkimmer, AbstractSkimmerInternal, AbstractSkimmerKey, AbstractSkimmerReadOnly}
import beam.router.skim.urbansim.ActivitySimOmxWriter
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject, ProfilingUtils}
import beam.utils.csv.CsvWriter
import com.google.inject.Inject
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.IterationEndsEvent

import java.io.BufferedWriter
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

  /*
  @author:haitamlaarabi
  ActivitySkimmer Workflow:

  ** Case A **
  taz:TAZ/CBG
     |
     v
  Skim_TAZ/CBG
     |
     v
  [DefaultSkim]

  ** Case B **
  taz:TAZ    geo:CBG
                /
            (selected) based on number zones i.e. higher resolution
              /
             v
        Skim_CBG --- (map) ---> Skim_TAZ
            |                      |
            v                      |
      Skim_CBG_Filtered            |
            |                      |
            v                      v
        MappedSkim            [DefaultSkim]

  ** Case C **
  taz:CBG    geo:TAZ
     |
 (selected) based on number zones i.e. higher resolution
     |
     v
  Skim_CBG --- (map) ---> Skim_TAZ
     |                        |
     |                        v
     |                 Skim_TAZ_Filtered
     |                        |
     v                        v
 [DefaultSkim]            MappedSkim
   */
  private def writePresentedSkims(filePath: String): Unit = {
    case class ActivitySimKey(
      timeBin: ActivitySimTimeBin,
      pathType: ActivitySimPathType,
      origin: String,
      destination: String
    )

    val mappedSkimFilePath = filePath.lastIndexOf(".") match {
      case -1    => filePath + "_geo_exchange"
      case index => filePath.substring(0, index) + "_geo_exchange" + filePath.substring(index)
    }

    val filterByBeamMode: ((ActivitySimKey, _)) => Boolean = { case (key, _) =>
      beamConfig.beam.exchange.output.secondary_activity_sim_skimmer.exists(
        _.beamModeFilter
          .contains(ActivitySimPathType.toBeamMode(key.pathType).value)
      )
    }

    val writeToOutput: (String, Iterable[ExcerptData], Seq[String]) => Unit = { case (filePath, data, geoUnits) =>
      try {
        config.activity_sim_skimmer.fileOutputFormat.trim.toLowerCase match {
          case "csv" =>
            val csvWriter = new CsvWriter(filePath, ExcerptData.csvHeaderSeq)
            csvWriter.writeAllAndClose(data.map(_.toCsvSeq))
          case _ =>
            ActivitySimOmxWriter.writeToOmx(filePath, data.iterator, geoUnits)
        }
      } catch {
        case exception: Exception =>
          logger.error(s"Cannot write skims to $filePath", exception)
      }
    }

    val transformData: Map[ActivitySimKey, Iterable[ActivitySimSkimmerInternal]] => Iterable[ExcerptData] = { data =>
      data.map { case (key, skimMap) =>
        weightedData(key.timeBin.entryName, key.origin, key.destination, key.pathType, skimMap.toList)
      }
    }

    ProfilingUtils.timed("Writing skims that are created during simulation ", x => logger.info(x)) {

      val excerptData: Map[ActivitySimKey, Seq[ActivitySimSkimmerInternal]] = currentSkim
        .asInstanceOf[Map[ActivitySimSkimmerKey, ActivitySimSkimmerInternal]]
        .toSeq
        .flatMap { case (key, value) =>
          val baseEntry = Seq(key -> value)
          if (isWalkTransit(key.pathType)) {
            // For walk-transit, duplicate the entry with modified pathType
            baseEntry :+ (key.copy(pathType = WLK_TRN_WLK) -> value)
          } else baseEntry
        }
        .groupBy { case (key, _) =>
          ActivitySimKey(ActivitySimTimeBin.toTimeBin(key.hour), key.pathType, key.origin, key.destination)
        }
        .mapValues(_.map(_._2))

      val maybeTazExcerptData = beamScenario.exchangeOutputGeoMap.map { exchangeGeoMap =>
        excerptData
          .collect { case (key, data) =>
            for {
              mappedOrigin      <- exchangeGeoMap.getMappedGeoId(key.origin)
              mappedDestination <- exchangeGeoMap.getMappedGeoId(key.destination)
            } yield ActivitySimKey(key.timeBin, key.pathType, mappedOrigin, mappedDestination) -> data
          }
          .flatten
          .groupBy(_._1)
          .mapValues(_.flatMap(_._2))
      }

      val (defaultExcerptData, maybeMappedExcerptData) =
        maybeTazExcerptData match {
          case Some(tazExcerptData) =>
            val geoMapSize = beamScenario.exchangeOutputGeoMap.get.getSize
            val tazMapSize = beamScenario.tazTreeMap.getSize
            val isGeoMapLarger = geoMapSize > tazMapSize
            if (isGeoMapLarger) (tazExcerptData, Some(excerptData.filter(filterByBeamMode)))
            else (excerptData, Some(tazExcerptData.filter(filterByBeamMode)))
          case None => (excerptData, None)
        }

      // Transform both default and maybeMapped data
      val defaultSkim = transformData(defaultExcerptData)
      val maybeMappedSkim = maybeMappedExcerptData.map(transformData)

      // Write default skim data
      writeToOutput(filePath, defaultSkim, beamScenario.tazTreeMap.orderedTazIds)

      // Write maybeMappedSkim data if present
      maybeMappedSkim.foreach(writeToOutput(mappedSkimFilePath, _, beamScenario.exchangeOutputGeoMap.get.orderedTazIds))
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

  def outputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("ActivitySimSkimmer", "activitySimODSkims_current.csv.gz", iterationLevel = true)(
      """
        timePeriod          | EA - early AM, 3 am to 6 am, AM - peak period, 6 am to 10 am, MD - midday period, 10 am to 3 pm, PM - peak period, 3 pm to 7 pm, EV - evening, 7 pm to 3 am the next day
        pathType            | See all the possible path types with descriptions https://activitysim.github.io/activitysim/v1.0.4/howitworks.html#skims
        origin              | Id of the origin geo unit
        destination         | Id of the destination geo unit
        TIME_minutes        | Travel time in minutes
        TOTIVT_IVT_minutes  | Total in-vehicle time (IVT) in minutes
        VTOLL_FAR           | Fare
        DIST_meters         | Travel distance in meters
        WACC_minutes        | Walk access time in minutes
        WAUX_minutes        | Walk other time in minutes
        WEGR_minutes        | Walk egress time in minutes
        DTIM_minutes        | Drive time in minutes
        DDIST_meters        | Drive distance in meters
        KEYIVT_minutes      | Light rail IVT
        FERRYIVT_minutes    | Ferry IVT
        BOARDS              | Number of transfers
        WeightedCost        | Weighted cost
        DEBUG_TEXT          | For internal use
        """
    )
}
