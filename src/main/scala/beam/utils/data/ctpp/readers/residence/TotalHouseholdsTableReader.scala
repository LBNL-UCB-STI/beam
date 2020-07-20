package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}
import beam.utils.data.ctpp.readers.residence.TotalHouseholdsTableReader.TotalHouseholds

class TotalHouseholdsTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.TotalHouseholds, Some(residenceGeography.level)) {

  def read(): TotalHouseholds = {
    val map = readRaw()
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // It is one to one relation, that's why we get the head
          geoId -> xs.head.estimate.toInt
      }
    TotalHouseholds(map)
  }
}

object TotalHouseholdsTableReader {
  case class TotalHouseholds(private val map: Map[String, Int]) extends Map[String, Int] {
    override def +[V1 >: Int](kv: (String, V1)): Map[String, V1] = map.+(kv)

    override def get(key: String): Option[Int] = map.get(key)

    override def iterator: Iterator[(String, Int)] = map.iterator

    override def -(key: String): Map[String, Int] = map.-(key)
  }

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val rdr = new TotalHouseholdsTableReader(databaseInfo, ResidenceGeography.State)
    val readData = rdr.read()
    readData.foreach {
      case (geoId, count) =>
        println(s"$geoId: $count")
    }
  }
}
