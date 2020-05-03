package beam.utils.data.ctpp.scenario

import java.util.Random

import beam.utils.data.ctpp.models._
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.residence.MeanHouseholdIncomeTableReader.MeanHouseholdIncome
import beam.utils.data.ctpp.readers.residence.MedianHouseholdIncomeTableReader.MedianHouseholdIncome
import beam.utils.data.ctpp.readers.residence.TotalHouseholdsTableReader.TotalHouseholds
import beam.utils.data.ctpp.readers.residence.TotalPopulationTableReader.TotalPopulation
import beam.utils.data.ctpp.readers.residence.{
  AgeTableReader,
  HouseholdSizeByUnitsInStructureTableReader,
  MeanHouseholdIncomeTableReader,
  MedianHouseholdIncomeTableReader,
  SexTableReader,
  TotalHouseholdsTableReader,
  TotalPopulationTableReader,
  UsualHoursWorkedPerWeekTableReader,
  VehiclesAvailableTableReader
}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.population.Population
import org.matsim.households.Households

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class SimpleScenarioGenerator(val pathToDoc: String, val dbInfo: CTPPDatabaseInfo, val javaRnd: Random)(
  implicit val ex: ExecutionContext
) extends ScenarioGenerator
    with StrictLogging {

  private val residenceGeography: ResidenceGeography = ResidenceGeography.TAZ

  // We read data in parallel. Not very smart, but better than sequential

  private val totalHouseholdsMapF = Future {
    new TotalHouseholdsTableReader(dbInfo, residenceGeography).read()
  }

  private val totalPopulationMapF = Future {
    new TotalPopulationTableReader(dbInfo, residenceGeography).read()
  }

  private val ageMapF = Future {
    new AgeTableReader(dbInfo, ResidenceGeography.State).read()
  }

  private val vehiclesAvailableMapF = Future {
    new VehiclesAvailableTableReader(dbInfo, residenceGeography).read()
  }

  private val sexMapF = Future {
    new SexTableReader(dbInfo, residenceGeography).read()
  }

  private val medianHouseholdIncomeMapF = Future {
    new MedianHouseholdIncomeTableReader(dbInfo, residenceGeography).read()
  }

  private val meanHouseholdIncomeMapF = Future {
    new MeanHouseholdIncomeTableReader(dbInfo, residenceGeography).read()
  }

  private val householdSizeMapF = Future {
    new HouseholdSizeByUnitsInStructureTableReader(dbInfo, residenceGeography).read()
  }
  private val usualHoursWorkedPerWeekMapF = Future {
    new UsualHoursWorkedPerWeekTableReader(dbInfo, residenceGeography).read()
  }
//
//  // Wait when data is read
//  Await.result(Future.sequence(List(ageMapF, totalPopulationMapF, vehiclesAvailableMapF,  sexMapF, medianHouseholdIncomeMapF, meanHouseholdIncomeMap, householdSizeMapF,
//    usualHoursWorkedPerWeekMapF)), readTimeout)

  override def generate: Future[(Households, Population)] = {
    for {
      totalHouseholds            <- totalHouseholdsMapF
      totalPopulationMap         <- totalPopulationMapF
      ageMap                     <- ageMapF
      vehiclesAvailableMap       <- vehiclesAvailableMapF
      sexMap                     <- sexMapF
      medianHouseholdIncomeMap   <- medianHouseholdIncomeMapF
      meanHouseholdIncomeMap     <- meanHouseholdIncomeMapF
      householdSizeMap           <- householdSizeMapF
      usualHoursWorkedPerWeekMap <- usualHoursWorkedPerWeekMapF
    } yield
      generate(
        totalHouseholds,
        totalPopulationMap,
        ageMap,
        vehiclesAvailableMap,
        sexMap,
        medianHouseholdIncomeMap,
        meanHouseholdIncomeMap,
        householdSizeMap,
        usualHoursWorkedPerWeekMap
      )
  }

  private def generate(
    totalHouseholds: TotalHouseholds,
    totalPopulation: TotalPopulation,
    ageMap: Map[String, Map[AgeRange, Double]],
    vehiclesAvailableMap: Map[String, Map[Vehicles, Double]],
    sexMap: Map[String, Map[Gender, Double]],
    medianHouseholdIncome: MedianHouseholdIncome,
    meanHouseholdIncome: MeanHouseholdIncome,
    householdSizeMap: Map[String, Map[HouseholdSize, Double]],
    usualHoursWorkedPerWeekMap: Map[String, Map[WorkedHours, Double]]
  ): (Households, Population) = {

    val allGeoIds = ageMap.keySet ++ totalPopulation.keySet ++ vehiclesAvailableMap.keySet ++ sexMap.keySet ++ medianHouseholdIncome.keySet ++ meanHouseholdIncome.keySet ++
    householdSizeMap.keySet ++ usualHoursWorkedPerWeekMap.keySet
    allGeoIds.foreach { geoId =>
      val households = totalHouseholds.get(geoId)
      val population = totalPopulation.get(geoId)
      val ages = ageMap.get(geoId)
      val vehiclesAvailable = vehiclesAvailableMap.get(geoId)
      val gender = sexMap.get(geoId)
      val medianIncome = medianHouseholdIncome.get(geoId)
      val meanIncome = meanHouseholdIncome.get(geoId)
      val householdSize = meanHouseholdIncome.get(geoId)
      val usualHoursWorkedPerWeek = usualHoursWorkedPerWeekMap.get(geoId)
      println()
    }
    (null, null)
  }

//  private val metadataReader = new MetadataReader(pathToDoc)

//  def exportTo(path: String): Unit = {
//    val csvWriter = new CsvWriter(path, headers)
//    try {
//      val tableIdToShellInfo = metadataReader
//        .readShellTable()
//        .filter(t => allTables.contains(t.tblId))
//        .groupBy { x =>
//          x.tblId
//        }
//      tableIdToShellInfo.foreach {
//        case (k, v) =>
//          println(s"$k => ${v.mkString(" ")}")
//      }
//      val ageMap = ageTableReader.read()
//      println(s"Read Age table: ${ageMap.size}")
//
//      val totalPopulationMap = totalPopulationTableReader.read()
//      println(s"Read Total population table: ${totalPopulationMap.size}")
//
//      val vehiclesAvailableMap = vehiclesAvailableTableReader.read()
//      println(s"Read Vehicles Available table: ${vehiclesAvailableMap.size}")
//
//      val genderMap = sexTableReader.read()
//      println(s"Read Sex table: ${genderMap.size}")
//
//      val medianHouseholdIncomeMap = medianHouseholdIncomeTableReader.read()
//      println(s"Read Median Household Income table: ${medianHouseholdIncomeMap.size}")
//
//      val meanHouseholdIncomeMap = meanHouseholdIncomeMap.read()
//      println(s"Read Mean Household Income table: ${meanHouseholdIncomeMap.size}")
//
//      val householdSizeMap = householdSizeMapF.read()
//      println(s"Read Household Size table: ${householdSizeMap.size}")
//
//      val usualHoursWorkedPerWeekMap = usualHoursWorkedPerWeekMapF.read()
//      println(s"Read Usual Hours worked per week table: ${usualHoursWorkedPerWeekMap.size}")
//
//      val populationInHouseholdsMap = CTPPParser
//        .readTable(pathToPopulationInHouseholds, filterOnlyTazGeoids)
//        .groupBy(x => x.geoId)
//
//      println(s"populationInHouseholdsMap size: ${populationInHouseholdsMap.size}")
//
//      val totalKeys = totalPopulationMap.keySet ++ vehiclesAvailableMap.keySet ++ populationInHouseholdsMap.keySet ++ ageMap.keySet
////      totalKeys.foreach { geoId =>
////        // It is one to one relation, that's why we get the head!
////        val totalHouseHolds = totalHouseholdMap.get(geoId).map(x => x.head.estimate).getOrElse {
////          logger.warn(s"There is no data for GEOID $geoId in `totalHouseholdMap`")
////          0.0
////        }
////        val vehiclesAvailable = vehiclesAvailableMap.get(geoId).map(x => x.head.estimate).getOrElse {
////          logger.warn(s"There is no data for GEOID $geoId in `vehiclesAvailableMap`")
////          0.0
////        }
////
////        // It is one to one relation, that's why we get the head!
////        val populationInHouseholds = populationInHouseholdsMap.get(geoId).map(x => x.head.estimate).getOrElse {
////          logger.warn(s"There is no data for GEOID $geoId in `populationInHouseholdsMap`")
////          0.0
////        }
////        println(totalHouseHolds)
////        println(vehiclesAvailable)
////        println(populationInHouseholds)
////      }
//
//      println(s"ageMap size: ${ageMap.size}")
//
//    } finally {
//      Try(csvWriter.close())
//    }
//  }

}

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    implicit val ex: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))

    val scenarioGenerator =
      new SimpleScenarioGenerator("D:/Work/beam/Austin/2012-2016 CTPP documentation", databaseInfo, new Random(42))
    val f = scenarioGenerator.generate
    val result = Await.result(f, 5.minutes)
    println(s"result: ${result}")
  }
}
