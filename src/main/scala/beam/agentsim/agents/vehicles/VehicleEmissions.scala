package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsRateFilterStore.EmissionsRateFilter
import beam.sim.common.{DoubleTypedRange, Range}
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.CsvParser
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, Future}

class VehicleEmissions {}

object VehicleEmissions {

  object EmissionsRateFilterStore {
    //speed->(gradePercent->(weight->(numberOfLanes->rate)))
    type EmissionsRateFilter = Map[DoubleTypedRange, Map[DoubleTypedRange, Map[DoubleTypedRange, Map[Range, Double]]]]
  }

  trait EmissionsRateFilterStore {

    def getEmissionsRateFilterFor(
      vehicleType: BeamVehicleType
    ): Option[Future[EmissionsRateFilterStore.EmissionsRateFilter]]

    def hasEmissionsRateFilterFor(vehicleType: BeamVehicleType): Boolean
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
  }

  class EmissionsRateFilterStoreImpl(
    baseFilePaths: IndexedSeq[String],
    emissionsRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])]
  ) extends EmissionsRateFilterStore {
    private lazy val log = LoggerFactory.getLogger(this.getClass)
    //Hard-coding can become configurable if necessary
    /*
    The following are placeholders, we haven't decided if they will be modelled or not.
    Although, I'm keeping them just in case.
     */
    private val gradeBinHeader = "grade_percent_float_bins"
    private val weightBinHeader = "mass_kg_float_bins"
    private val geoAreaHeader = "geo_area"
    /*
    The following represents the main header for the emissions rate file.
    EmissionsSource column can either be:
        Running (i.e. Running Exhaust)
        Start (i.e. Start Exhaust)
        Hoteling (i.e. Hoteling Exhaust)
        Dust (i.e. Brake/tire wear and road dust)
        Evaporative (i.e. Evaporative emissions)
        Other (also if the EmissionsSource column is not present or the value is not recognized then)
    rate_XXX is in grams per mile
     */
    private val speedBinHeader = "speed_mph_float_bins"
    private val emissionsSource = "emissions_source"
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
    override def getEmissionsRateFilterFor(vehicleType: BeamVehicleType): Option[Future[EmissionsRateFilter]] = {}
    override def hasEmissionsRateFilterFor(vehicleType: BeamVehicleType): Boolean = ???

    private def getVehicleEmissionsRecordsUsing(csvParser: CsvParser, filePath: String): Iterable[Record] = {
      csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
    }

    private def loadConsumptionRatesFromCSVFor(
      file: String,
      csvParser: CsvParser,
      fuelTypeOption: Option[FuelType]
    ): EmissionsRateFilterStore.EmissionsRateFilter = {
      import beam.utils.BeamVehicleUtils._
      val currentRateFilter = mutable.Map
        .empty[DoubleTypedRange, mutable.Map[
          DoubleTypedRange,
          mutable.Map[DoubleTypedRange, mutable.Map[Range, Double]]
        ]]
      baseFilePaths.foreach(baseFilePath =>
        getVehicleEmissionsRecordsUsing(csvParser, java.nio.file.Paths.get(baseFilePath, file).toString)
          .foreach(csvRecord => {
            val speedInMilesPerHourBin = convertRecordStringToDoubleTypedRange(csvRecord.getString(speedBinHeader))
            val gradePercentBin = convertRecordStringToDoubleTypedRange(csvRecord.getString(gradeBinHeader))
            val numberOfLanesBin = if (csvRecord.getMetaData.containsColumn(geoAreaHeader)) {
              convertRecordStringToRange(csvRecord.getString(lanesBinHeader))
            } else {
              convertRecordStringToRange("(0,100]")
            }
            val weightKgBin = if (csvRecord.getMetaData.containsColumn(weightBinHeader)) {
              convertRecordStringToDoubleTypedRange(csvRecord.getString(weightBinHeader))
            } else {
              convertRecordStringToDoubleTypedRange("(0,50000]")
            }
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
            val rates = EmissionsRates(
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
            if (rates.notValid)
              throw new Exception(
                s"Record $csvRecord does not contain a valid rate. " +
                "Erroring early to bring attention and get it fixed."
              )
          })
      )
    }

  }

}
