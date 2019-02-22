package beam.agentsim.agents.vehicles

import beam.sim.common.Range
import beam.sim.config.BeamConfig
import com.google.inject.Inject
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import com.univocity.parsers.common.record.Record
import java.io.File

import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.JavaConverters._

class VehicleCsvReader(config: BeamConfig) {

  def getVehicleEnergyRecordsUsing(csvParser: CsvParser): Iterable[Record] = {
    val csvLocation = config.beam.agentsim.agents.vehicles.vehicleEnergyFile
    csvParser.iterateRecords(IOUtils.getBufferedReader(csvLocation)).asScala
  }

  def getLinkToGradeRecordsUsing(csvParser: CsvParser): Iterable[Record] = {
    val csvLocation = config.beam.agentsim.agents.vehicles.linkToGradePercentFile
    csvParser.iterateRecords(IOUtils.getBufferedReader(csvLocation)).asScala
  }
}

class VehicleEnergy(
  vehicleEnergyRecordsIterableUsing: CsvParser => Iterable[Record],
  linkToGradeRecordsIterableUsing: CsvParser => Iterable[Record]
) {
  private lazy val log = LoggerFactory.getLogger(this.getClass)
  private val settings = new CsvParserSettings()
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  type ConsumptionRateFilter = Map[Range, Map[Range, Map[Range, Double]]] //speed->(gradePercent->(numberOfLanes->rate))
  private lazy val consumptionRateFilter = loadConsumptionRatesFromCSV
  private lazy val linkIdToGradePercentMap = loadLinkIdToGradeMapFromCSV
  val joulesPerMeterConversionRate = 746.86

  def getFuelConsumptionEnergyInJoulesUsing(
    fuelConsumptionDatas: IndexedSeq[BeamVehicle.FuelConsumptionData],
    fallBack: (IndexedSeq[BeamVehicle.FuelConsumptionData]) => Double
  ) = {
    val consumptionsInJoules = fuelConsumptionDatas
      .map(fuelConsumptionData => {
        for {
          rateInJoulesPerMeter <- getRateUsing(fuelConsumptionData)
          distance             <- fuelConsumptionData.linkLength
        } yield rateInJoulesPerMeter * distance
      })
      .flatten
    if (consumptionsInJoules.size > 0) consumptionsInJoules.sum else fallBack(fuelConsumptionDatas)
  }

  private def getRateUsing(fuelConsumptionData: BeamVehicle.FuelConsumptionData): Option[Double] = {
    val BeamVehicle.FuelConsumptionData(linkId, _, numberOfLanesOption, _, _, _, speedInMilesPerHourOption, _, _, _) =
      fuelConsumptionData
    val numberOfLanes = numberOfLanesOption.getOrElse(0)
    val speedInMilesPerHour = speedInMilesPerHourOption.map(_.toInt).getOrElse(0)
    val gradePercent = linkIdToGradePercentMap.get(linkId).getOrElse(0)
    //Future performance improvement could be to better index the bins so could fuzzily jump straight to it
    //instead of having to iterate
    val filteredRates = consumptionRateFilter
      .collect {
        case (speedInMilesPerHourBin, restOfFilter) if speedInMilesPerHourBin.has(speedInMilesPerHour) =>
          restOfFilter
      }
      .flatten
      .collect { case (gradePercentBin, restOfFilter) if gradePercentBin.has(gradePercent) => restOfFilter }
      .flatten
      .collect { case (numberOfLanesBin, rate) if numberOfLanesBin.has(numberOfLanes) => rate }

    val ratesSize = filteredRates.size
    if (ratesSize > 1)
      log.warn(
        "More than one ({}) rate was found using {}. " +
        "The first will be used, but the data should be reviewed for range overlap.",
        ratesSize,
        fuelConsumptionData
      )
    filteredRates.headOption.map(convertToJoulesPerMeter)
  }

  private def convertToJoulesPerMeter(rate: Double): Double = rate * joulesPerMeterConversionRate

  private def loadLinkIdToGradeMapFromCSV: Map[Int, Int] = {
    linkToGradeRecordsIterableUsing(csvParser)
      .map(csvRecord => {
        val linkId = csvRecord.getInt(0)
        val gradePercent = csvRecord.getDouble(1)
        (linkId.toInt -> gradePercent.toInt)
      })
      .toMap
  }

  private def loadConsumptionRatesFromCSV: ConsumptionRateFilter = {
    val currentRateFilter = mutable.Map.empty[Range, mutable.Map[Range, mutable.Map[Range, Double]]]
    vehicleEnergyRecordsIterableUsing(csvParser).foreach(csvRecord => {
      val speedInMilesPerHourBin = convertRecordStringToRange(csvRecord.getString(0))
      val gradePercentBin = convertRecordStringToRange(csvRecord.getString(1))
      val numberOfLanesBin = convertRecordStringToRange(csvRecord.getString(2))
      val rate = csvRecord.getDouble(5)
      if (rate == null)
        throw new Exception(
          s"Record $csvRecord does not contain a valid rate. " +
          "Erroring early to bring attention and get it fixed."
        )

      currentRateFilter.get(speedInMilesPerHourBin) match {
        case Some(gradePercentFilter) => {
          gradePercentFilter.get(gradePercentBin) match {
            case Some(numberOfLanesFilter) => {
              numberOfLanesFilter.get(numberOfLanesBin) match {
                case Some(initialRate) =>
                  log.error(
                    "Two rates found for the same bin combination: " +
                    "Speed In Miles Per Hour Bin = {}; Grade Percent Bin = {}; Number of Lanes Bin = {}. " +
                    s"Keeping initial rate of $initialRate and ignoring new rate of $rate.",
                    speedInMilesPerHourBin,
                    gradePercentBin,
                    numberOfLanesBin
                  )
                case None => numberOfLanesFilter += numberOfLanesBin -> rate
              }
            }
            case None => gradePercentFilter += gradePercentBin -> mutable.Map(numberOfLanesBin -> rate)
          }
        }
        case None =>
          currentRateFilter += speedInMilesPerHourBin ->
          mutable.Map(gradePercentBin -> mutable.Map(numberOfLanesBin -> rate))
      }
    })
    currentRateFilter.toMap.map {
      case (speedInMilesPerHourBin, gradePercentMap) =>
        speedInMilesPerHourBin -> gradePercentMap.toMap.map {
          case (gradePercentBin, lanesMap) => gradePercentBin -> lanesMap.toMap
        }
    }
  }

  private def convertRecordStringToRange(recordString: String) = {
    Range(recordString.replace(",", ":").replace(" ", ""))
  }
}
