package scripts.ctpp.readers.residence

import scripts.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}
import MedianHouseholdIncomeTableReader.MedianHouseholdIncome
import scripts.ctpp.models.ResidenceGeography
import scripts.ctpp.readers.BaseTableReader

class MedianHouseholdIncomeTableReader(dbInfo: CTPPDatabaseInfo, val residenceGeography: ResidenceGeography)
    extends BaseTableReader(dbInfo, Table.Residence.MedianHouseholdIncome, Some(residenceGeography.level)) {

  def read(): MedianHouseholdIncome = {
    val map = readRaw()
      .groupBy(x => x.geoId)
      .map { case (geoId, xs) =>
        // It is one to one relation, that's why we get the head
        val income = xs.head.estimate
        geoId -> income
      }
    MedianHouseholdIncome(map)
  }

}

object MedianHouseholdIncomeTableReader {

  case class MedianHouseholdIncome(private val map: Map[String, Double]) extends Map[String, Double] {
    override def +[V1 >: Double](kv: (String, V1)): Map[String, V1] = map.+(kv)

    override def get(key: String): Option[Double] = map.get(key)

    override def iterator: Iterator[(String, Double)] = map.iterator

    override def -(key: String): Map[String, Double] = map.-(key)
  }
}
