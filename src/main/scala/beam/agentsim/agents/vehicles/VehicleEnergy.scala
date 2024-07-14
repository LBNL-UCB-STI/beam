package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleEnergy._
import beam.sim.common.{DoubleTypedRange, Range}
import beam.utils.BeamVehicleUtils
import beam.sim.config.BeamConfig
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

class VehicleEnergy(consumptionRateFilterStore: ConsumptionRateFilterStore, beamConfig: BeamConfig) {
  private val settings = new CsvParserSettings()
  settings.setHeaderExtractionEnabled(true)
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  private lazy val linkIdToGradePercentMap = BeamVehicleUtils.loadLinkIdToGradeMapFromCSV(csvParser, beamConfig)
  private val conversionRateForMilesPerHourFromMetersPerSecond = 2.23694

  def vehicleEnergyMappingExistsFor(vehicleType: BeamVehicleType): Boolean = {
    consumptionRateFilterStore.hasPrimaryConsumptionRateFilterFor(vehicleType) ||
    consumptionRateFilterStore.hasSecondaryConsumptionRateFilterFor(vehicleType)
  }

  def getFuelConsumptionEnergyInJoulesUsing(
    fuelConsumptionDatas: IndexedSeq[BeamVehicle.VehicleActivityData],
    fallBack: Double,
    powerTrainPriority: PowerTrainPriority
  ): Double = {
    val consumptionsInJoules: IndexedSeq[Double] = fuelConsumptionDatas
      .map(fuelConsumptionData => {
        val rateInJoulesPerMeter = getRateUsing(fuelConsumptionData, fallBack, powerTrainPriority)
        val distance = fuelConsumptionData.linkLength.getOrElse(0.0)
        val finalConsumption = rateInJoulesPerMeter * distance
        finalConsumption
      })
    consumptionsInJoules.sum
  }

  private def getRateUsing(
    fuelConsumptionData: BeamVehicle.VehicleActivityData,
    fallBack: Double,
    powerTrainPriority: PowerTrainPriority
  ): Double = {
    if (!vehicleEnergyMappingExistsFor(fuelConsumptionData.vehicleType)) { fallBack }
    else {
      val BeamVehicle.VehicleActivityData(
        linkId,
        vehicleType,
        payloadKg,
        numberOfLanesOption,
        _,
        _,
        speedInMetersPerSecondOption,
        _,
        _,
        _,
        _,
        _,
        _,
        _
      ) = fuelConsumptionData
      val numberOfLanes: Int = numberOfLanesOption.getOrElse(0)
      val speedInMilesPerHour: Double = speedInMetersPerSecondOption
        .map(convertFromMetersPerSecondToMilesPerHour)
        .getOrElse(0)
      val weightKg: Double = fuelConsumptionData.vehicleType.curbWeightInKg + payloadKg.getOrElse(0.0)
      val gradePercent: Double = linkIdToGradePercentMap.getOrElse(linkId, 0)
      (powerTrainPriority match {
        case Primary   => consumptionRateFilterStore.getPrimaryConsumptionRateFilterFor(vehicleType)
        case Secondary => consumptionRateFilterStore.getSecondaryConsumptionRateFilterFor(vehicleType)

      }).flatMap(consumptionRateFilterFuture =>
        getRateUsing(consumptionRateFilterFuture, numberOfLanes, speedInMilesPerHour, weightKg, gradePercent)
      ).getOrElse(fallBack)
    }
  }

  private def getRateUsing(
    consumptionRateFilterFuture: Future[ConsumptionRateFilterStore.ConsumptionRateFilter],
    numberOfLanes: Int,
    speedInMilesPerHour: Double,
    weightKg: Double,
    gradePercent: Double
  ): Option[Double] = {
    //1.)Future performance improvement could be to better index the bins so could fuzzily jump straight to it
    //instead of having to iterate
    //2.)Could keep the future in the calling method if you use
    //Future.sequence and Option.option2Iterable followed by a flatMap(_.headOption),
    //but that gets complicated and these SHOULD already be loaded by the time they are needed.
    //If that changes then go ahead and map through the collections
    import scala.concurrent.duration._
    val consumptionRateFilter = Await.result(consumptionRateFilterFuture, 1.minute)

    for {
      (_, gradeFilter) <- consumptionRateFilter
        .find { case (speedInMilesPerHourBin, _) => speedInMilesPerHourBin.has(speedInMilesPerHour) }
      (_, weightFilter) <- gradeFilter.find { case (gradePercentBin, _) => gradePercentBin.has(gradePercent) }
      (_, lanesFilter)  <- weightFilter.find { case (weightPercentBin, _) => weightPercentBin.has(weightKg) }
      (_, rate)         <- lanesFilter.find { case (numberOfLanesBin, _) => numberOfLanesBin.has(numberOfLanes) }
    } yield rate
  }

  // Future Performance Improvement: Change so that ConsumptionRateFilterStoreImpl does the conversion.
  // But this will require an official DoubleRange to avoid precision loss,
  // and we don't want to mess with that at this point
  private def convertFromMetersPerSecondToMilesPerHour(mps: Double): Double =
    mps * conversionRateForMilesPerHourFromMetersPerSecond
}

object VehicleEnergy {
  sealed trait PowerTrainPriority
  case object Primary extends PowerTrainPriority
  case object Secondary extends PowerTrainPriority

  private def getVehicleEnergyRecordsUsing(csvParser: CsvParser, filePath: String): Iterable[Record] = {
    csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
  }

  object ConsumptionRateFilterStore {
    //speed->(gradePercent->(weight->(numberOfLanes->rate)))
    type ConsumptionRateFilter = Map[DoubleTypedRange, Map[DoubleTypedRange, Map[DoubleTypedRange, Map[Range, Double]]]]
  }

  trait ConsumptionRateFilterStore {

    def getPrimaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[ConsumptionRateFilterStore.ConsumptionRateFilter]]

    def getSecondaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[ConsumptionRateFilterStore.ConsumptionRateFilter]]
    def hasPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean
    def hasSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean
  }

  class ConsumptionRateFilterStoreImpl(
    baseFilePaths: IndexedSeq[String],
    primaryConsumptionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])],
    secondaryConsumptionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
  ) extends ConsumptionRateFilterStore {
    //Possible performance tweak: If memory becomes an issue then this can become a more on demand load
    //For now, and for load speed the best option seems to be to pre-load in a background thread
    private lazy val log = LoggerFactory.getLogger(this.getClass)
    //Hard-coding can become configurable if necessary
    private val speedBinHeader = "speed_mph_float_bins"
    private val gradeBinHeader = "grade_percent_float_bins"
    private val lanesBinHeader = "num_lanes_int_bins"
    private val weightBinHeader = "mass_kg_float_bins"
    private val rateHeader = "rate"
    private val conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles = 746.86
    private val conversionRateForJoulesPerMeterConversionFromKwhPer100Miles = 22.37

    private val primaryConsumptionRateFiltersByVehicleType
      : Map[BeamVehicleType, Future[ConsumptionRateFilterStore.ConsumptionRateFilter]] =
      beginLoadingConsumptionRateFiltersFor(
        primaryConsumptionRateFilePathsByVehicleType,
        bvt => Some(bvt.primaryFuelType)
      )

    private val secondaryConsumptionRateFiltersByVehicleType
      : Map[BeamVehicleType, Future[ConsumptionRateFilterStore.ConsumptionRateFilter]] =
      beginLoadingConsumptionRateFiltersFor(secondaryConsumptionRateFilePathsByVehicleType, _.secondaryFuelType)

    def getPrimaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[ConsumptionRateFilterStore.ConsumptionRateFilter]] =
      primaryConsumptionRateFiltersByVehicleType.get(vehicleType)

    def getSecondaryConsumptionRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[ConsumptionRateFilterStore.ConsumptionRateFilter]] =
      secondaryConsumptionRateFiltersByVehicleType.get(vehicleType)

    def hasPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean =
      primaryConsumptionRateFiltersByVehicleType.keySet.contains(vehicleType)

    def hasSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Boolean =
      secondaryConsumptionRateFiltersByVehicleType.keySet.contains(vehicleType)

    private def beginLoadingConsumptionRateFiltersFor(
      files: IndexedSeq[(BeamVehicleType, Option[String])],
      fuelTypeSelector: BeamVehicleType => Option[FuelType]
    ): Map[BeamVehicleType, Future[ConsumptionRateFilterStore.ConsumptionRateFilter]] = {
      files.collect {
        case (vehicleType, Some(filePath)) if filePath.trim.nonEmpty =>
          val consumptionFuture = Future {
            //Do NOT move this out - sharing the parser between threads is questionable
            val settings = new CsvParserSettings()
            settings.setHeaderExtractionEnabled(true)
            settings.detectFormatAutomatically()
            val csvParser = new CsvParser(settings)
            loadConsumptionRatesFromCSVFor(filePath, csvParser, fuelTypeSelector(vehicleType))
          }
          consumptionFuture.failed.map(ex => log.error(s"Error while loading consumption rate filter", ex))
          vehicleType -> consumptionFuture
      }.toMap
    }

    private def loadConsumptionRatesFromCSVFor(
      file: String,
      csvParser: CsvParser,
      fuelTypeOption: Option[FuelType]
    ): ConsumptionRateFilterStore.ConsumptionRateFilter = {
      import beam.utils.BeamVehicleUtils._
      val currentRateFilter = mutable.Map
        .empty[DoubleTypedRange, mutable.Map[
          DoubleTypedRange,
          mutable.Map[DoubleTypedRange, mutable.Map[Range, Double]]
        ]]
      baseFilePaths.foreach(baseFilePath =>
        getVehicleEnergyRecordsUsing(csvParser, java.nio.file.Paths.get(baseFilePath, file).toString)
          .foreach(csvRecord => {
            val speedInMilesPerHourBin = convertRecordStringToDoubleTypedRange(csvRecord.getString(speedBinHeader))
            val gradePercentBin = convertRecordStringToDoubleTypedRange(csvRecord.getString(gradeBinHeader))
            val numberOfLanesBin = if (csvRecord.getMetaData.containsColumn(lanesBinHeader)) {
              convertRecordStringToRange(csvRecord.getString(lanesBinHeader))
            } else {
              convertRecordStringToRange("(0,100]")
            }
            val weightKgBin = if (csvRecord.getMetaData.containsColumn(weightBinHeader)) {
              convertRecordStringToDoubleTypedRange(csvRecord.getString(weightBinHeader))
            } else {
              convertRecordStringToDoubleTypedRange("(0,200000]")
            }
            val rawRate = csvRecord.getDouble(rateHeader)
            if (rawRate == null)
              throw new Exception(
                s"Record $csvRecord does not contain a valid rate. " +
                "Erroring early to bring attention and get it fixed."
              )
            val rate =
              if (fuelTypeOption.contains(FuelType.Electricity)) convertFromKwhPer100MilesToJoulesPerMeter(rawRate)
              else convertFromGallonsPer100MilesToJoulesPerMeter(rawRate)

            currentRateFilter.get(speedInMilesPerHourBin) match {
              case Some(gradePercentFilter) =>
                gradePercentFilter.get(gradePercentBin) match {
                  case Some(weightKgFilter) =>
                    weightKgFilter.get(weightKgBin) match {
                      case Some(numberOfLanesFilter) =>
                        numberOfLanesFilter.get(numberOfLanesBin) match {
                          case Some(firstRate) =>
                            val rawFirstRate =
                              if (fuelTypeOption.contains(FuelType.Electricity))
                                convertFromJoulesPerMeterToKwhPer100Miles(firstRate)
                              else convertFromJoulesPerMeterToGallonsPer100Miles(firstRate)
                            log.error(
                              "Two rates found for the same bin combination: " +
                              "Speed In Miles Per Hour Bin = {}; Grade Percent Bin = {}; Weight kg Bin = {};" +
                              " Number of Lanes Bin = {}. " +
                              s"Keeping first rate of $rawFirstRate and ignoring new rate of $rawRate.",
                              speedInMilesPerHourBin,
                              gradePercentBin,
                              weightKgBin,
                              numberOfLanesBin
                            )
                          case None => numberOfLanesFilter += numberOfLanesBin -> rate
                        }
                      case None => weightKgFilter += weightKgBin -> mutable.Map(numberOfLanesBin -> rate)
                    }
                  case None =>
                    gradePercentFilter += gradePercentBin -> mutable.Map(
                      weightKgBin -> mutable.Map(numberOfLanesBin -> rate)
                    )
                }
              case None =>
                currentRateFilter += speedInMilesPerHourBin ->
                mutable.Map(gradePercentBin -> mutable.Map(weightKgBin -> mutable.Map(numberOfLanesBin -> rate)))
            }
          })
      )
      currentRateFilter.toMap.map { case (speedInMilesPerHourBin, gradePercentMap) =>
        speedInMilesPerHourBin -> gradePercentMap.toMap.map { case (gradePercentBin, weightMap) =>
          gradePercentBin -> weightMap.toMap.map { case (weightKgBin, lanesMap) => weightKgBin -> lanesMap.toMap }
        }
      }
    }

    private def convertFromGallonsPer100MilesToJoulesPerMeter(rate: Double): Double =
      rate * conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles

    private def convertFromJoulesPerMeterToGallonsPer100Miles(rate: Double): Double =
      rate / conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles

    private def convertFromKwhPer100MilesToJoulesPerMeter(rate: Double): Double =
      rate * conversionRateForJoulesPerMeterConversionFromKwhPer100Miles

    private def convertFromJoulesPerMeterToKwhPer100Miles(rate: Double): Double =
      rate / conversionRateForJoulesPerMeterConversionFromKwhPer100Miles
  }
}
