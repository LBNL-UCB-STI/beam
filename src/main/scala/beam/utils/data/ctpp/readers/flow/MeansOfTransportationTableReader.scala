package beam.utils.data.ctpp.readers.flow

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, MeansOfTransportation, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class MeansOfTransportationTableReader(
  pathToData: PathToData,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(pathToData, Table.MeanOfTransportation, Some(residenceToWorkplaceFlowGeography.level)) {
  private val interestedLineNumber: Set[Int] = MeansOfTransportation.all.map(_.lineNumber).toSet

  def read(): Seq[OD[MeansOfTransportation]] = {
    CTPPParser
      .readTable(pathToCsvTable, x => geographyLevelFilter(x) && interestedLineNumber.contains(x.lineNumber))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        val mode = MeansOfTransportation(entry.lineNumber).get
        OD(fromGeoId, toGeoId, mode, entry.estimate)
      }
  }
}

object MeansOfTransportationTableReader {

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Provide the path to the data folder CTPP")
    val pathToData = args(0)
    val ods =
      new MeansOfTransportationTableReader(PathToData(pathToData), ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`)
        .read()
    println(s"Read ${ods.size} OD pairs")
  }
}
