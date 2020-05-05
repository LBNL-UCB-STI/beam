package beam.utils.data.ctpp.readers.flow

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, MeansOfTransportation, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}

class MeansOfTransportationTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(dbInfo, Table.MeanOfTransportation, Some(residenceToWorkplaceFlowGeography.level)) {
  private val interestedLineNumber: Set[Int] = MeansOfTransportation.all.map(_.lineNumber).toSet

  def read(): Iterable[OD[MeansOfTransportation]] = {
    readRaw()
      .filter(x => interestedLineNumber.contains(x.lineNumber))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        val mode = MeansOfTransportation(entry.lineNumber).get
        OD(fromGeoId, toGeoId, mode, entry.estimate)
      }
  }
}

object MeansOfTransportationTableReader {

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val ods =
      new MeansOfTransportationTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`)
        .read()
    println(s"Read ${ods.size} OD pairs")
  }
}
