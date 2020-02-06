package beam.utils.data.ctpp.converter

import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.Models.CTPPEntry
import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader.Table
import beam.utils.data.ctpp.readers.{
  AgeTableReader,
  BaseTableReader,
  MetadataReader,
  SexTableReader,
  TotalPopulationTableReader,
  VehiclesAvailableTableReader
}
import beam.utils.data.ctpp.{CTPPParser, Metadata}
import com.typesafe.scalalogging.StrictLogging

import scala.util.Try

class HouseholdPopulationConverter(val pathToDoc: String, pathToData: String) extends StrictLogging {
  import HouseholdPopulationConverter._

  private val pathToTotalHouseholds: String = BaseTableReader.findFile(pathToData, Table.TotalHouseholds.name)
  private val pathToVehiclesAvailable: String = BaseTableReader.findFile(pathToData, Table.VehiclesAvailable.name)
  private val pathToPopulationInHouseholds: String =
    BaseTableReader.findFile(pathToData, Table.PopulationInHouseholds.name)
  private val pathToAge: String = BaseTableReader.findFile(pathToData, Table.Age.name)

  private val allTables: Set[String] =
    Set(Table.TotalHouseholds.name, Table.VehiclesAvailable.name, Table.PopulationInHouseholds.name, Table.Age.name)

  private val headers: Array[String] = Array("geoid", "households", "population")

  private val residenceGeography: ResidenceGeography = ResidenceGeography.TAZ
  private val ageTableReader = new AgeTableReader(pathToData, residenceGeography)
  private val totalPopulationTableReader = new TotalPopulationTableReader(pathToData, residenceGeography)
  private val vehiclesAvailableTableReader = new VehiclesAvailableTableReader(pathToData, residenceGeography)
  private val sexTableReader = new SexTableReader(pathToData, residenceGeography)

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
      println(tableIdToShellInfo.mkString(" "))

      val ageMap = ageTableReader.read()
      println(s"Read Age table: ${ageMap.size}")

      val totalPopulationMap = totalPopulationTableReader.read()
      println(s"Read Total population table: ${totalPopulationMap.size}")

      val vehiclesAvailableMap = vehiclesAvailableTableReader.read()
      println(s"Read Vehicles Available table: ${vehiclesAvailableMap.size}")

      val genderMap = sexTableReader.read()
      println(s"Read Sex table: ${genderMap.size}")

      val totalHouseholdMap = CTPPParser
        .readTable(pathToTotalHouseholds, filterOnlyTazGeoids)
        .groupBy(x => x.geoId)
      println(s"totalHouseholdMap size: ${totalHouseholdMap.size}")

      val populationInHouseholdsMap = CTPPParser
        .readTable(pathToPopulationInHouseholds, filterOnlyTazGeoids)
        .groupBy(x => x.geoId)

      println(s"populationInHouseholdsMap size: ${populationInHouseholdsMap.size}")

      val totalKeys = totalHouseholdMap.keySet ++ vehiclesAvailableMap.keySet ++ populationInHouseholdsMap.keySet ++ ageMap.keySet
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
