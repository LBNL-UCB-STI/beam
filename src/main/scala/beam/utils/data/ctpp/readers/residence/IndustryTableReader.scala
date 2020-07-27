package beam.utils.data.ctpp.readers.residence

import beam.utils.data.ctpp.models.residence.Industry
import beam.utils.data.ctpp.models.{ResidenceGeography, ResidentialGeoParser}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}

/*
TableShell(A102214,2,2,Agriculture, forestry, fishing and hunting, and mining)
TableShell(A102214,3,2,Construction)
TableShell(A102214,4,2,Manufacturing)
TableShell(A102214,5,2,Wholesale trade)
TableShell(A102214,6,2,Retail trade)
TableShell(A102214,7,2,Transportation and warehousing, and utilities)
TableShell(A102214,8,2,Information)
TableShell(A102214,9,2,Finance, insurance, real estate and rental and leasing)
TableShell(A102214,10,2,Professional, scientific, management, administrative,  and waste management services)
TableShell(A102214,11,2,Educational, health and social services)
TableShell(A102214,12,2,Arts, entertainment, recreation, accommodation and food services)
TableShell(A102214,13,2,Other services (except public administration))
TableShell(A102214,14,2,Public administration)
TableShell(A102214,15,2,Armed forces)
 */

// Reads total counts only for all industry by all occupations
class IndustryTableReader(
  dbInfo: CTPPDatabaseInfo,
  val geo: ResidenceGeography
) extends BaseTableReader(
      dbInfo,
      Table.Residence.OccupationByIndustry,
      Some(geo.level)
    ) {

  def read(): Map[String, Array[(Industry, Double)]] = {
    val res = readRaw()
      .filter(x => x.lineNumber >= 2 && x.lineNumber <= 15) // lineNumber == 1 is Total
      .toArray
      .map { entry =>
        val geoId = ResidentialGeoParser.parse(entry.geoId).get
        val industry = Industry(entry.lineNumber).get
        (geoId, (industry, entry.estimate))
      }
      .groupBy { case (geoId, xs) => geoId }
      .map {
        case (geoId, xs) =>
          geoId -> xs.map(_._2)
      }
    res
  }
}
