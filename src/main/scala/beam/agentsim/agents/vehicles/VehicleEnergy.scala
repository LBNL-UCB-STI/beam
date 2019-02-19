package beam.agentsim.agents.vehicles

import beam.sim.common.Range
import beam.sim.config.BeamConfig
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import com.univocity.parsers.common.record.Record
import java.io.File
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.collection.JavaConverters._

class VehicleEnergyCsvReader(config: BeamConfig) {

  def getRecordsFrom(csvFile: File, csvParser: CsvParser): Iterable[Record] = {
    val csvLocation = config.beam.agentsim.agents.vehicles.vehicleEnergyFile
    csvParser.iterateRecords(new File(csvLocation)).asScala
  }
}

case class TravelData(speedInMilesPerHour: Int, gradePercent: Int, numberOfLanes: Int)

class VehicleEnergy(recordsIterableUsing: CsvParser => Iterable[Record]) {
  private lazy val log = LoggerFactory.getLogger(this.getClass)

  type ConsumptionRateFilter = Map[Range, Map[Range, Map[Range, Float]]] //speed->(gradePercent->(numberOfLanes->rate))
  private lazy val consumptionRateFilter = loadConsumptionRatesFromCSV

  def getRateUsing(travelData: TravelData, fallBack: => Float): Float = {
    val TravelData(speedInMilesPerHour, gradePercent, numberOfLanes) = travelData
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
        travelData
      )
    filteredRates.headOption.getOrElse(fallBack)
  }

  private def loadConsumptionRatesFromCSV: ConsumptionRateFilter = {
    val currentRateFilter = mutable.Map.empty[Range, mutable.Map[Range, mutable.Map[Range, Float]]]
    val settings = new CsvParserSettings()
    settings.detectFormatAutomatically()
    val csvParser = new CsvParser(settings)
    recordsIterableUsing(csvParser).foreach(csvRecord => {
      val speedInMilesPerHourBin = convertRecordStringToRange(csvRecord.getString(0))
      val gradePercentBin = convertRecordStringToRange(csvRecord.getString(1))
      val numberOfLanesBin = convertRecordStringToRange(csvRecord.getString(2))
      val rate = csvRecord.getFloat(5)
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
