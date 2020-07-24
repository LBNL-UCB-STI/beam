package beam.utils.data.ctpp.readers.workplace

import beam.utils.data.ctpp.models.ResidenceGeography
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}

class IndustryTableReader(
  dbInfo: CTPPDatabaseInfo,
  val geo: ResidenceGeography
) extends BaseTableReader(
      dbInfo,
      Table.Residence.Industry,
      Some(geo.level)
    ) {

  def read(): Map[String, Map[String, Double]] = {
    readRaw()
      .filter(x => x.lineNumber != 1) // lineNumber == 1 is Total
      .map { entry =>

//        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
//        val industry = Industry(entry.lineNumber).get
//        OD(fromGeoId, toGeoId, industry, entry.estimate)
      }
    ???
  }
}
