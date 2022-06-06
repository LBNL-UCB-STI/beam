package scripts.ctpp.readers.flow

import scripts.ctpp.models.flow.Industry
import scripts.ctpp.models.{FlowGeoParser, OD, ResidenceToWorkplaceFlowGeography}
import scripts.ctpp.readers.BaseTableReader
import scripts.ctpp.readers.BaseTableReader.CTPPDatabaseInfo
import scripts.ctpp.readers.BaseTableReader.Table

class IndustryTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(
      dbInfo,
      Table.Flow.Industry,
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
