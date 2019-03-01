package beam.agentsim.agents.vehicles

import beam.sim.common.Range
import beam.sim.config.BeamConfig
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import com.univocity.parsers.common.record.Record
import org.matsim.core.utils.io.IOUtils
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

//TODO: Fix/Add Tests
//TODO: Handle header files and column by name instead of index
//Question: What to do if the file is not found or cannot be read - exception and die?
//TODO: Create an issue: Add a test checking that Consumption Rates used (integration test)
//TODO: Create an issue: Handle secondary fuel - see https://github.com/LBNL-UCB-STI/beam/pull/1296#discussion_r260605865

class VehicleCsvReader(config: BeamConfig) {
  def getVehicleEnergyRecordsUsing(csvParser: CsvParser, filePath: String): Iterable[Record] = {
    csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
  }

  def getLinkToGradeRecordsUsing(csvParser: CsvParser): Iterable[Record] = {
    val filePath = config.beam.agentsim.agents.vehicles.linkToGradePercentFile
    csvParser.iterateRecords(IOUtils.getBufferedReader(filePath)).asScala
  }
}

trait ConsumptionRateFilterStore{
  type ConsumptionRateFilter = Map[Range, Map[Range, Map[Range, Double]]] //speed->(gradePercent->(numberOfLanes->rate))
  def getPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Option[Future[ConsumptionRateFilter]]
  def getSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType): Option[Future[ConsumptionRateFilter]]
}

class ConsumptionRateFilterStoreImpl(
  csvRecordsForFilePathUsing:(CsvParser, String) => Iterable[Record],
  baseFilePath: Option[String],
  primaryConsumptionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])],
  secondaryConsumptionRateFilePathsByVehicleType: IndexedSeq[(BeamVehicleType, Option[String])])
  extends ConsumptionRateFilterStore {
  //Possible performance tweak: If memory becomes an issue then this can become a more on demand load
  //For now, and for load speed the best option seems to be to pre-load in a background thread
  private lazy val log = LoggerFactory.getLogger(this.getClass)

  private val primaryConsumptionRateFiltersByVehicleType: Map[BeamVehicleType, Future[ConsumptionRateFilter]] =
    beginLoadingConsumptionRateFiltersFor(primaryConsumptionRateFilePathsByVehicleType)
  private val secondaryConsumptionRateFiltersByVehicleType: Map[BeamVehicleType, Future[ConsumptionRateFilter]] =
    beginLoadingConsumptionRateFiltersFor(secondaryConsumptionRateFilePathsByVehicleType)

  def getPrimaryConsumptionRateFilterFor(vehicleType: BeamVehicleType) =
    primaryConsumptionRateFiltersByVehicleType.get(vehicleType)
  def getSecondaryConsumptionRateFilterFor(vehicleType: BeamVehicleType) =
    secondaryConsumptionRateFiltersByVehicleType.get(vehicleType)

  private def beginLoadingConsumptionRateFiltersFor(files: IndexedSeq[(BeamVehicleType, Option[String])]) = {
    files.collect{
      case (vehicleType, Some(filePath)) => {
        val consumptionFuture = Future{
          //Do NOT move this out - sharing the parser between threads is questionable
          val settings = new CsvParserSettings()
          settings.detectFormatAutomatically()
          val csvParser = new CsvParser(settings)
          loadConsumptionRatesFromCSVFor(filePath, csvParser)
        }
        consumptionFuture.failed.onComplete(ex=>
          log.error(ex.getOrElse("Unknown error loading consumption rate filter").toString))
        vehicleType -> consumptionFuture
      }
    }.toMap
  }

  private def loadConsumptionRatesFromCSVFor(file: String, csvParser: CsvParser): ConsumptionRateFilter = {
    val currentRateFilter = mutable.Map.empty[Range, mutable.Map[Range, mutable.Map[Range, Double]]]
    csvRecordsForFilePathUsing(csvParser, java.nio.file.Paths.get(baseFilePath.getOrElse(""), file).toString).foreach(csvRecord => {
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

class VehicleEnergy(
  consumptionRateFilterStore: ConsumptionRateFilterStore,
  linkToGradeRecordsIterableUsing: CsvParser => Iterable[Record]
) {
  private lazy val log = LoggerFactory.getLogger(this.getClass)
  private val settings = new CsvParserSettings()
  settings.detectFormatAutomatically()
  private val csvParser = new CsvParser(settings)

  type ConsumptionRateFilter = Map[Range, Map[Range, Map[Range, Double]]] //speed->(gradePercent->(numberOfLanes->rate))
  private lazy val linkIdToGradePercentMap = loadLinkIdToGradeMapFromCSV
  val conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles = 746.86

  def getFuelConsumptionEnergyInJoulesUsing(
    fuelConsumptionDatas: IndexedSeq[BeamVehicle.FuelConsumptionData],
    fallBack: BeamVehicle.FuelConsumptionData => Double
  ): Double = {
    val consumptionsInJoules: IndexedSeq[Double] = fuelConsumptionDatas
      .map(fuelConsumptionData => {
        val rateInJoulesPerMeter = getRateUsing(fuelConsumptionData, fallBack)
        val distance = fuelConsumptionData.linkLength.getOrElse(0.0)
        rateInJoulesPerMeter * distance
      })
    consumptionsInJoules.sum
  }

  private def getRateUsing(fuelConsumptionData: BeamVehicle.FuelConsumptionData,
                           fallBack: BeamVehicle.FuelConsumptionData => Double): Double = {
    val BeamVehicle.FuelConsumptionData(linkId, vehicleType, numberOfLanesOption, _, _, _, speedInMilesPerHourOption, _, _, _) =
      fuelConsumptionData
    val numberOfLanes: Int = numberOfLanesOption.getOrElse(0)
    val speedInMilesPerHour: Double = speedInMilesPerHourOption.getOrElse(0)
    val gradePercent: Double = linkIdToGradePercentMap.getOrElse(linkId, 0)
    consumptionRateFilterStore.getPrimaryConsumptionRateFilterFor(vehicleType).flatMap(
      consumptionRateFilterFuture => getRateUsing(consumptionRateFilterFuture,
        numberOfLanes, speedInMilesPerHour, gradePercent)
    ).getOrElse(fallBack(fuelConsumptionData))
  }

  private def getRateUsing(consumptionRateFilterFuture: Future[ConsumptionRateFilter],
                            numberOfLanes: Int, speedInMilesPerHour: Double, gradePercent: Double): Option[Double] = {
    //1.)Future performance improvement could be to better index the bins so could fuzzily jump straight to it
    //instead of having to iterate
    //2.)Could keep the future in the calling method if you use
    //Future.sequence and Option.option2Iterable followed by a flatMap(_.headOption),
    //but that gets complicated and these SHOULD already be loaded by the time they are needed.
    //If that changes then go ahead and map through the collections
    import scala.concurrent.duration._
    val consumptionRateFilter = Await.result(consumptionRateFilterFuture, 1.minute)

    for{
      (_, gradeFilter) <- consumptionRateFilter
        .find { case (speedInMilesPerHourBin, _) => speedInMilesPerHourBin.hasDouble(speedInMilesPerHour)}
      (_, lanesFilter) <- gradeFilter.find { case (gradePercentBin, _) => gradePercentBin.hasDouble(gradePercent) }
      (_, rate) <- lanesFilter.find { case (numberOfLanesBin, _) => numberOfLanesBin.has(numberOfLanes) }
    } yield convertFromGallonsPer100MilesToJoulesPerMeter(rate)
  }

  private def convertFromGallonsPer100MilesToJoulesPerMeter(rate: Double): Double =
    rate * conversionRateForJoulesPerMeterConversionFromGallonsPer100Miles

  private def loadLinkIdToGradeMapFromCSV: Map[Int, Double] = {
    linkToGradeRecordsIterableUsing(csvParser)
      .map(csvRecord => {
        val linkId = csvRecord.getInt(0)
        val gradePercent = csvRecord.getDouble(1)
        linkId.toInt -> gradePercent.toDouble
      })
      .toMap
  }
}
