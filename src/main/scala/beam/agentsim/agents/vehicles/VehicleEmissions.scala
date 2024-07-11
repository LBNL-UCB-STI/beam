package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsRateFilterStore
import beam.sim.common.{DoubleTypedRange, Range}
import beam.sim.config.BeamConfig
import beam.utils.BeamVehicleUtils
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

class VehicleEmissions(emissionRateFilterStore: EmissionsRateFilterStore) {
  private val settings = new CsvParserSettings()
  settings.setHeaderExtractionEnabled(true)
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  def vehicleEmissionsMappingExistsFor(vehicleType: BeamVehicleType): Boolean =
    emissionRateFilterStore.hasEmissionsRateFilterFor(vehicleType)

//  def getFuelConsumptionEnergyInJoulesUsing(
//    fuelConsumptionDatas: IndexedSeq[BeamVehicle.FuelConsumptionData],
//    fallBack: Double
//  ): Double = {}

  private def getRateUsing(
    consumptionRateFilterFuture: Future[EmissionsRateFilterStore.EmissionsRateFilter],
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

  private def getRatesUsing(
    fuelConsumptionData: BeamVehicle.FuelConsumptionData,
    fallBack: VehicleEmissions.EmissionsRates = VehicleEmissions.defaultEmissionsRates
  ): VehicleEmissions.EmissionsRates = {
    if (!vehicleEmissionsMappingExistsFor(fuelConsumptionData.vehicleType)) { fallBack }
    else {
      val BeamVehicle.FuelConsumptionData(
        linkId,
        vehicleType,
        payloadKgOption,
        _,
        _,
        _,
        speedInMetersPerSecondOption,
        _,
        _,
        _,
        _
      ) = fuelConsumptionData
      val speedInMilesPerHour: Double = speedInMetersPerSecondOption
        .map(BeamVehicleUtils.convertFromMetersPerSecondToMilesPerHour)
        .getOrElse(0)
      val weightKg: Double = fuelConsumptionData.vehicleType.curbWeightInKg + payloadKgOption.getOrElse(0.0)
      val gradePercent: Double = linkIdToGradePercentMap.getOrElse(linkId, 0)
      emissionRateFilterStore
        .getEmissionsRateFilterFor(vehicleType)
        .flatMap(emissionRateFilterFuture =>
          getRateUsing(consumptionRateFilterFuture, numberOfLanes, speedInMilesPerHour, weightKg, gradePercent)
        )
        .getOrElse(fallBack)
    }
  }

}

object VehicleEmissions {

  object EmissionsRateFilterStore {

    //speed->(gradePercent->(weight->(numberOfLanes->rate)))
    type EmissionsRateFilter =
      Map[
        DoubleTypedRange,
        Map[DoubleTypedRange, Map[DoubleTypedRange, Map[String, Map[EmissionsSource.EmissionsSource, EmissionsRates]]]]
      ]
  }

  trait EmissionsRateFilterStore {

    def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]]

    def hasEmissionsRateFilterFor(vehicleType: BeamVehicleType): Boolean
  }

  object EmissionsSource extends Enumeration {
    type EmissionsSource = Value
    val Running, Start, Hotelling, Dust, Evaporative, Other = Value

    def fromString(source: String): EmissionsSource = source.toLowerCase match {
      case "running" | "running exhaust"            => Running
      case "start" | "start exhaust"                => Start
      case "hotelling" | "hotelling exhaust"        => Hotelling
      case "dust" | "brake/tire wear and road dust" => Dust
      case "evaporative" | "evaporative emissions"  => Evaporative
      case _                                        => Other
    }
  }

  case class EmissionsRates(
    CH4: Double,
    CO: Double,
    CO2: Double,
    HC: Double,
    NH3: Double,
    NOx: Double,
    PM: Double,
    PM10: Double,
    PM2_5: Double,
    ROG: Double,
    SOx: Double,
    TOG: Double
  ) {
    def notValid: Boolean = List(CH4, CO, CO2, HC, NH3, NOx, PM, PM10, PM2_5, ROG, SOx, TOG).forall(_ == 0)

    override def toString: String = {
      s"EmissionsRates(CH4=$CH4, CO=$CO, CO2=$CO2, HC=$HC, NH3=$NH3, NOx=$NOx, PM=$PM, PM10=$PM10, PM2_5=$PM2_5, ROG=$ROG, SOx=$SOx, TOG=$TOG)"
    }
  }

  val defaultEmissionsRates: EmissionsRates = EmissionsRates(
    CH4 = 0.0,
    CO = 0.0,
    CO2 = 0.0,
    HC = 0.0,
    NH3 = 0.0,
    NOx = 0.0,
    PM = 0.0,
    PM10 = 0.0,
    PM2_5 = 0.0,
    ROG = 0.0,
    SOx = 0.0,
    TOG = 0.0
  )

  class EmissionsRateFilterStoreImpl(
    baseFilePaths: IndexedSeq[String],
    emissionsRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
  ) extends EmissionsRateFilterStore {
    private lazy val log = LoggerFactory.getLogger(this.getClass)
    //Hard-coding can become configurable if necessary
    private val speedBinHeader = "speed_mph_float_bins"
    private val gradeBinHeader = "grade_percent_float_bins"
    private val weightBinHeader = "mass_kg_float_bins"
    private val geoAreaHeader = "geo_area"
    /*
    EmissionsSource column can either be:
        Running (i.e. Running Exhaust)
        Start (i.e. Start Exhaust)
        Hoteling (i.e. Hoteling Exhaust)
        Dust (i.e. Brake/tire wear and road dust)
        Evaporative (i.e. Evaporative emissions)
        Other (also if the EmissionsSource column is not present or the value is not recognized then)
    rate_XXX is in grams per mile
     */
    private val emissionsSourceHeader = "emissions_source"

    /*
     * rateXXX is in grams per mile
     * */
    private val rateCH4Header = "rate_ch4_gpm_float"
    private val rateCOHeader = "rate_co_gpm_float"
    private val rateCO2Header = "rate_co2_gpm_float"
    private val rateHCHeader = "rate_hc_gpm_float"
    private val rateNH3Header = "rate_nh3_gpm_float"
    private val rateNOxHeader = "rate_nox_gpm_float"
    private val ratePMHeader = "rate_pm_gpm_float"
    private val ratePM10Header = "rate_pm10_gpm_float"
    private val ratePM2_5Header = "rate_pm2_5_gpm_float"
    private val rateROGHeader = "rate_rog_gpm_float"
    private val rateSOxHeader = "rate_sox_gpm_float"
    private val rateTOGHeader = "rate_tog_gpm_float"

    private val emissionRateFiltersByVehicleType
      : Map[BeamVehicleType, Future[EmissionsRateFilterStore.EmissionsRateFilter]] =
      beginLoadingEmissionRateFiltersFor(emissionsRateFilePathsByVehicleType)

    override def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]] = emissionRateFiltersByVehicleType.get(vehicleType)

    override def hasEmissionsRateFilterFor(vehicleType: BeamVehicleType): Boolean =
      emissionRateFiltersByVehicleType.keySet.contains(vehicleType)

    private def getVehicleEmissionsRecordsUsing(csvParser: CsvParser, filePath: String): Iterable[Record] = {
      csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
    }

    private def beginLoadingEmissionRateFiltersFor(
      files: IndexedSeq[(BeamVehicleType, Option[String])]
    ): Map[BeamVehicleType, Future[EmissionsRateFilterStore.EmissionsRateFilter]] = {
      files.collect {
        case (vehicleType, Some(filePath)) if filePath.trim.nonEmpty =>
          val consumptionFuture = Future {
            //Do NOT move this out - sharing the parser between threads is questionable
            val settings = new CsvParserSettings()
            settings.setHeaderExtractionEnabled(true)
            settings.detectFormatAutomatically()
            val csvParser = new CsvParser(settings)
            loadEmissionRatesFromCSVFor(filePath, csvParser)
          }
          consumptionFuture.failed.map(ex => log.error(s"Error while loading emission rate filter", ex))
          vehicleType -> consumptionFuture
      }.toMap
    }

    private def loadEmissionRatesFromCSVFor(
      file: String,
      csvParser: CsvParser
    ): EmissionsRateFilterStore.EmissionsRateFilter = {
      import beam.utils.BeamVehicleUtils._

      val currentRateFilter = mutable.Map.empty[DoubleTypedRange, mutable.Map[
        DoubleTypedRange,
        mutable.Map[DoubleTypedRange, mutable.Map[String, mutable.Map[EmissionsSource.EmissionsSource, EmissionsRates]]]
      ]]

      baseFilePaths.foreach(baseFilePath =>
        getVehicleEmissionsRecordsUsing(csvParser, java.nio.file.Paths.get(baseFilePath, file).toString)
          .foreach(csvRecord => {
            // Speed Bin in MPH
            val speedInMilesPerHourBin = convertRecordStringToDoubleTypedRange(csvRecord.getString(speedBinHeader))
            // Road Grade Bin in PERCENTAGE
            val gradePercentBin = if (csvRecord.getMetaData.containsColumn(gradeBinHeader)) {
              convertRecordStringToDoubleTypedRange(csvRecord.getString(gradeBinHeader))
            } else {
              convertRecordStringToDoubleTypedRange("(-100,100]")
            }
            // Weight in Kg
            val weightKgBin = if (csvRecord.getMetaData.containsColumn(weightBinHeader)) {
              convertRecordStringToDoubleTypedRange(csvRecord.getString(weightBinHeader))
            } else {
              convertRecordStringToDoubleTypedRange("(0,200000]")
            }
            // Geographic area, None if not defined
            val geographicArea = if (csvRecord.getMetaData.containsColumn(geoAreaHeader)) {
              csvRecord.getString(geoAreaHeader)
            } else {
              ""
            }
            // Emission source
            val emissionSource = EmissionsSource.fromString(csvRecord.getString(emissionsSourceHeader))

            def readRateCheckIfNull(headerName: String): Double = {
              val value = csvRecord.getDouble(headerName)
              if (value == null) {
                log.warn(
                  s"Record $csvRecord does not contain a valid rate for $headerName. " +
                  "Warning early to bring attention and get it fixed if not intended."
                )
                0.0
              } else value
            }

            // Emissions Rates in Grans Per Mile
            val ratesInGramsPerMile = EmissionsRates(
              CH4 = readRateCheckIfNull(rateCH4Header),
              CO = readRateCheckIfNull(rateCOHeader),
              CO2 = readRateCheckIfNull(rateCO2Header),
              HC = readRateCheckIfNull(rateHCHeader),
              NH3 = readRateCheckIfNull(rateNH3Header),
              NOx = readRateCheckIfNull(rateNOxHeader),
              PM = readRateCheckIfNull(ratePMHeader),
              PM10 = readRateCheckIfNull(ratePM10Header),
              PM2_5 = readRateCheckIfNull(ratePM2_5Header),
              ROG = readRateCheckIfNull(rateROGHeader),
              SOx = readRateCheckIfNull(rateSOxHeader),
              TOG = readRateCheckIfNull(rateTOGHeader)
            )
            if (ratesInGramsPerMile.notValid)
              throw new Exception(
                s"Record $csvRecord does not contain a valid rate. " +
                "Erroring early to bring attention and get it fixed."
              )

            currentRateFilter.get(speedInMilesPerHourBin) match {
              case Some(gradePercentFilter) =>
                gradePercentFilter.get(gradePercentBin) match {
                  case Some(weightKgFilter) =>
                    weightKgFilter.get(weightKgBin) match {
                      case Some(geographicAreaFilter) =>
                        geographicAreaFilter.get(geographicArea) match {
                          case Some(emissionsSourceFilter) =>
                            emissionsSourceFilter.get(emissionSource) match {
                              case Some(existingRates) =>
                                log.error(
                                  "Two emission rates found for the same bin combination: " +
                                  "Geographic Area = {}; Speed In Miles Per Hour Bin = {}; " +
                                  "Grade Percent Bin = {}; Weight kg Bin = {}; Number of Lanes Bin = {}. " +
                                  s"Keeping first rate of $existingRates and ignoring new rate of $ratesInGramsPerMile.",
                                  geographicArea,
                                  speedInMilesPerHourBin,
                                  gradePercentBin,
                                  weightKgBin
                                )
                              case None => emissionsSourceFilter += emissionSource -> ratesInGramsPerMile
                            }
                          case None =>
                            geographicAreaFilter += geographicArea -> mutable.Map(emissionSource -> ratesInGramsPerMile)
                        }
                      case None =>
                        weightKgFilter += weightKgBin -> mutable.Map(geographicArea -> ratesInGramsPerMile)
                    }
                  case None =>
                    gradePercentFilter += gradePercentBin -> mutable.Map(
                      weightKgBin -> mutable.Map(geographicArea -> ratesInGramsPerMile)
                    )
                }
              case None =>
                currentRateFilter += speedInMilesPerHourBin -> mutable.Map(
                  gradePercentBin -> mutable.Map(weightKgBin -> mutable.Map(geographicArea -> ratesInGramsPerMile))
                )
            }
          })
      )
      currentRateFilter.toMap.map { case (speedInMilesPerHourBin, gradePercentMap) =>
        speedInMilesPerHourBin -> gradePercentMap.toMap.map { case (gradePercentBin, weightMap) =>
          gradePercentBin -> weightMap.toMap.map { case (weightKgBin, geoAreaMap) =>
            weightKgBin -> geoAreaMap.toMap.map { case (geoArea, emissionsSourceMap) =>
              geoArea -> emissionsSourceMap.toMap
            }
          }
        }
      }
    }
  }
}
