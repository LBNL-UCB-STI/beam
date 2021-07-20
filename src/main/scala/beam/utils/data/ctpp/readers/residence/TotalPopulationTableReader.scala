package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.{ResidenceGeoParser, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}
import beam.utils.data.ctpp.readers.residence.TotalPopulationTableReader.TotalPopulation

class TotalPopulationTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.PopulationInHouseholds, Some(residenceGeography.level)) {

  def read(): TotalPopulation = {
    val map = readRaw()
      .groupBy(x => x.geoId)
      .flatMap { case (geoId, xs) =>
        // It is one to one relation, that's why we get the head
        ResidenceGeoParser.parse(geoId).map(parseGeoId => parseGeoId -> xs.head.estimate.toInt).toOption
      }
    TotalPopulation(map)
  }
}

object TotalPopulationTableReader {

  case class TotalPopulation(private val map: Map[String, Int]) extends Map[String, Int] {
    override def +[V1 >: Int](kv: (String, V1)): Map[String, V1] = map.+(kv)

    override def get(key: String): Option[Int] = map.get(key)

    override def iterator: Iterator[(String, Int)] = map.iterator

    override def -(key: String): Map[String, Int] = map.-(key)
  }

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val readData = new TotalPopulationTableReader(databaseInfo, ResidenceGeography.TAZ).read()

    println(s"Number of keys: ${readData.size}")
    val totalNumberOfPeopleInAllGeoIds = readData.values.sum
    readData.foreach { case (geoId, count) =>
      println(s"$geoId: $count")
    }
    println(s"totalNumberOfPeopleInAllGeoIds: $totalNumberOfPeopleInAllGeoIds")
  }
}
