package beam.utils.data.ctpp.converter

import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.Models.CTPPEntry
import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}
import beam.utils.data.ctpp.readers.{
  AgeTableReader,
  BaseTableReader,
  HouseholdSizeByUnitsInStructureTableReader,
  MeanHouseholdIncomeTableReader,
  MedianHouseholdIncomeTableReader,
  MetadataReader,
  SexTableReader,
  TotalPopulationTableReader,
  VehiclesAvailableTableReader
}
import beam.utils.data.ctpp.CTPPParser
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

class HouseholdPopulationConverter(val pathToDoc: String, pathToData: String) extends StrictLogging {
  import HouseholdPopulationConverter._

  private val pathToPopulationInHouseholds: String =
    BaseTableReader.findFile(pathToData, Table.PopulationInHouseholds.name)
  private val pathToAge: String = BaseTableReader.findFile(pathToData, Table.Age.name)

  private val allTables: Set[String] =
    Set(
      Table.TotalHouseholds,
      Table.VehiclesAvailable,
      Table.PopulationInHouseholds,
      Table.Age,
      Table.MedianHouseholdIncome,
      Table.MeanHouseholdIncome,
      Table.Sex,
      Table.HouseholdSizeByUnitsInStructure
    ).map(_.name)

  private val headers: Array[String] = Array("geoid", "households", "population")

  private val residenceGeography: ResidenceGeography = ResidenceGeography.TAZ
  private val ageTableReader = new AgeTableReader(PathToData(pathToData), residenceGeography)
  private val totalPopulationTableReader = new TotalPopulationTableReader(PathToData(pathToData), residenceGeography)
  private val vehiclesAvailableTableReader =
    new VehiclesAvailableTableReader(PathToData(pathToData), residenceGeography)
  private val sexTableReader = new SexTableReader(PathToData(pathToData), residenceGeography)

  private val medianHouseholdIncomeTableReader =
    new MedianHouseholdIncomeTableReader(PathToData(pathToData), residenceGeography)
  private val meanHouseholdIncomeTableReader =
    new MeanHouseholdIncomeTableReader(PathToData(pathToData), residenceGeography)
  private val householdSizeByUnitsInStructureTableReader =
    new HouseholdSizeByUnitsInStructureTableReader(PathToData(pathToData), residenceGeography)

  private val metadataReader = new MetadataReader(pathToDoc)

  def filterOnlyTazGeoids(x: CTPPEntry): Boolean = {
    x.geoId.startsWith(ResidenceGeography.TAZ.level)
  }

  def exportTo(path: String): Unit = {
    val csvWriter = new CsvWriter(path, headers)
    try {
      val tableIdToShellInfo = metadataReader
        .readShellTable()
        .filter(t => allTables.contains(t.tblId))
        .groupBy { x =>
          x.tblId
        }
      tableIdToShellInfo.foreach {
        case (k, v) =>
          println(s"$k => ${v.mkString(" ")}")
      }
      val ageMap = ageTableReader.read()
      println(s"Read Age table: ${ageMap.size}")

      val totalPopulationMap = totalPopulationTableReader.read()
      println(s"Read Total population table: ${totalPopulationMap.size}")

      val vehiclesAvailableMap = vehiclesAvailableTableReader.read()
      println(s"Read Vehicles Available table: ${vehiclesAvailableMap.size}")

      val genderMap = sexTableReader.read()
      println(s"Read Sex table: ${genderMap.size}")

      val medianHouseholdIncomeMap = medianHouseholdIncomeTableReader.read()
      println(s"Read Median Household Income table: ${medianHouseholdIncomeMap.size}")

      val meanHouseholdIncomeMap = meanHouseholdIncomeTableReader.read()
      println(s"Read Mean Household Income table: ${meanHouseholdIncomeMap.size}")

      val householdSizeMap = householdSizeByUnitsInStructureTableReader.read()
      println(s"Read Household Size table: ${householdSizeMap.size}")

      val populationInHouseholdsMap = CTPPParser
        .readTable(pathToPopulationInHouseholds, filterOnlyTazGeoids)
        .groupBy(x => x.geoId)

      println(s"populationInHouseholdsMap size: ${populationInHouseholdsMap.size}")

      val totalKeys = totalPopulationMap.keySet ++ vehiclesAvailableMap.keySet ++ populationInHouseholdsMap.keySet ++ ageMap.keySet
//      totalKeys.foreach { geoId =>
//        // It is one to one relation, that's why we get the head!
//        val totalHouseHolds = totalHouseholdMap.get(geoId).map(x => x.head.estimate).getOrElse {
//          logger.warn(s"There is no data for GEOID $geoId in `totalHouseholdMap`")
//          0.0
//        }
//        val vehiclesAvailable = vehiclesAvailableMap.get(geoId).map(x => x.head.estimate).getOrElse {
//          logger.warn(s"There is no data for GEOID $geoId in `vehiclesAvailableMap`")
//          0.0
//        }
//
//        // It is one to one relation, that's why we get the head!
//        val populationInHouseholds = populationInHouseholdsMap.get(geoId).map(x => x.head.estimate).getOrElse {
//          logger.warn(s"There is no data for GEOID $geoId in `populationInHouseholdsMap`")
//          0.0
//        }
//        println(totalHouseHolds)
//        println(vehiclesAvailable)
//        println(populationInHouseholds)
//      }

      println(s"ageMap size: ${ageMap.size}")

    } finally {
      Try(csvWriter.close())
    }
  }
}

object HouseholdPopulationConverter {

  def main(args: Array[String]): Unit = {
    val householdPopulationConverter = new HouseholdPopulationConverter(
      "D:/Work/beam/Austin/2012-2016 CTPP documentation",
      "D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48"
    )
    householdPopulationConverter.exportTo("C://temp/1.csv")
  }
}
