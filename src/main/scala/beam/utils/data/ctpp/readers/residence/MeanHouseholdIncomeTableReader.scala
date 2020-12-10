package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}
import beam.utils.data.ctpp.readers.residence.MeanHouseholdIncomeTableReader.MeanHouseholdIncome

class MeanHouseholdIncomeTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.MeanHouseholdIncome, Some(residenceGeography.level)) {

  def read(): MeanHouseholdIncome = {
    val map = readRaw()
      .groupBy(x => x.geoId)
      .map {
        case (geoId, xs) =>
          // It is one to one relation, that's why we get the head
          val income = xs.head.estimate
          geoId -> income
      }
    MeanHouseholdIncome(map)
  }

}

object MeanHouseholdIncomeTableReader {
  case class MeanHouseholdIncome(private val map: Map[String, Double]) extends Map[String, Double] {
    override def +[V1 >: Double](kv: (String, V1)): Map[String, V1] = map.+(kv)

    override def get(key: String): Option[Double] = map.get(key)

    override def iterator: Iterator[(String, Double)] = map.iterator

    override def -(key: String): Map[String, Double] = map.-(key)
  }
}
