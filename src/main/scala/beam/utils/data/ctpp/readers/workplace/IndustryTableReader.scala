package beam.utils.data.ctpp.readers.workplace

import beam.utils.data.ctpp.models.{FlowGeoParser, ResidenceGeography, ResidentialGeoParser}
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

  def read(): Map[String, (Int, Double)] = {
    readRaw()
      .filter(x => x.lineNumber != 1) // lineNumber == 1 is Total
      .map { entry =>
        val geoId = ResidentialGeoParser.parse(entry.geoId).get
        (geoId, (entry.lineNumber, entry.estimate))
      }
      .groupBy { case (geoId, xs) =>
        geoId -> xs
      }
    ???
  }
}
