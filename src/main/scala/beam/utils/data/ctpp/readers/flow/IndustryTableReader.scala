package beam.utils.data.ctpp.readers.flow

import beam.utils.data.ctpp.models.{FlowGeoParser, Industry, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, Table}

class IndustryTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(
      dbInfo,
      Table.Industry,
      Some(residenceToWorkplaceFlowGeography.level)
    ) {

  def read(): Iterable[OD[Industry]] = {
    readRaw()
      .filter(x => x.lineNumber != 1) // lineNumber == 1 is Total
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId)
        val industry = Industry(entry.lineNumber).get
        OD(fromGeoId, toGeoId, industry, entry.estimate)
      }
  }
}
