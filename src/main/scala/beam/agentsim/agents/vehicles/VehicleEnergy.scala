package beam.agentsim.agents.vehicles

import beam.sim.common.Range
import beam.sim.config.BeamConfig
import com.univocity.parsers.csv.{CsvParserSettings, CsvParser}
import com.univocity.parsers.common.record.Record
import java.io.File
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.collection.JavaConverters._

class VehicleEnergyCSVReader(config: BeamConfig){
  def getRecordsFrom(csvFile: File): Iterable[Record] = {
    val settings = new CsvParserSettings()
    settings.detectFormatAutomatically()
    val parser = new CsvParser(settings)
    val csvLocation = config.beam.agentsim.populationAdjustment
    parser.iterateRecords(new File(csvLocation)).asScala
  }
}

case class TravelData(speed:Int, gradePercent: Int, numberOfLanes: Int)
class VehicleEnergy(recordIterator: => Iterable[Record]) {
  private lazy val log = LoggerFactory.getLogger(this.getClass)

  type ConsumptionRateFilter = Map[Range, Map[Range, Map[Range, Float]]] //speed->(gradePercent->(numberOfLanes->rate))
  private lazy val consumptionRateFilter = loadConsumptionRatesFromCSV

  def getRateUsing(travelData: TravelData, fallBack: => Float): Float = {
    val TravelData(speed, gradePercent, numberOfLanes) = travelData
    //Future performance improvement could be to better index the bins so could fuzzily jump straight to it
    //instead of having to iterate
    val filteredRates = consumptionRateFilter
      .collect{case (speedBin, restOfFilter) if speedBin.has(speed) => restOfFilter}.flatten
      .collect{case (gradePercentBin, restOfFilter) if gradePercentBin.has(gradePercent) => restOfFilter}.flatten
      .collect{case (numberOfLanesBin, rate) if numberOfLanesBin.has(numberOfLanes) => rate}

    val ratesSize = filteredRates.size
    if(ratesSize > 1) log.warn("More than one ({}) rate was found using {}. " +
      "The first will be used, but the data should be reviewed for range overlap.", ratesSize, travelData)
    filteredRates.headOption.getOrElse(fallBack)
  }

  private def loadConsumptionRatesFromCSV: ConsumptionRateFilter = {
    val currentRateFilter = mutable.Map.empty[Range, mutable.Map[Range, mutable.Map[Range, Float]]]
    recordIterator.foreach(csvRecord => {
      val speedBin = Range(csvRecord.getString(0).replace(",",":").replace(" ",""))
      val gradePercentBin = Range(csvRecord.getString(1).replace(",",":").replace(" ",""))
      val numberOfLanesBin = Range(csvRecord.getString(2).replace(",",":").replace(" ",""))
      val rateStr = csvRecord.getString(5)
      println(csvRecord.getValues.toList)
      println(rateStr)
      val rate = rateStr.toFloat
      currentRateFilter.get(speedBin) match {
        case Some(gradePercentFilter) => {
          gradePercentFilter.get(gradePercentBin) match {
            case Some(numberOfLanesFilter) => {
              numberOfLanesFilter.get(numberOfLanesBin) match {
                case Some(initialRate) =>
                  println(initialRate)
                  println(rate)
                  log.error("Two rates found for the same bin combination: " +
                    "Speed Bin = {}; Grade Percent Bin = {}; Number of Lanes Bin = {}. " +
                    "Keeping initial rate of {} and ignoring new rate of {}.",
                    speedBin.toString, gradePercentBin.toString, numberOfLanesBin.toString, initialRate.toString, rate.toString)
                case None => numberOfLanesFilter += numberOfLanesBin -> rate
              }
            }
            case None => gradePercentFilter += gradePercentBin -> mutable.Map(numberOfLanesBin -> rate)
          }
        }
        case None => currentRateFilter += speedBin -> mutable.Map(gradePercentBin -> mutable.Map(numberOfLanesBin -> rate))
      }
    })
    println(currentRateFilter)
    currentRateFilter.toMap.map{
      case (speedBin, gradePercentMap) => speedBin -> gradePercentMap.toMap.map{
        case (gradePercentBin, lanesMap) => gradePercentBin -> lanesMap.toMap
      }
    }
  }
}
