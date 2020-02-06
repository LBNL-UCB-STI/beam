package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}
import beam.utils.data.ctpp.readers.TotalHouseholdsTableReader.TotalHouseholds

class TotalHouseholdsTableReader(pathToData: PathToData, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(pathToData, Table.TotalHouseholds, Some(residenceGeography.level)) {

  def read(): TotalHouseholds = {
    val map = CTPPParser
      .readTable(pathToCsvTable, geographyLevelFilter)
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
}
