package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{MeansOfTransportation, OD}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class MeansOfTransportationTableReader(pathToData: PathToData)
  extends BaseTableReader(pathToData, Table.MeanOfTransportation, Some("C56")) {
    private val interestedLineNumber: Set[Int] = MeansOfTransportation.all.map(_.lineNumber).toSet

    def read(): Seq[OD[MeansOfTransportation]] = {
      CTPPParser
        .readTable(pathToCsvTable, x => geographyLevelFilter(x) && interestedLineNumber.contains(x.lineNumber))
        .map { entry =>
          // C56	CTPP Flow- TAZ-to-TAZ	st/cty/taz/st/cty/taz	C5600USssccczzzzzzzzssccczzzzzzzz
          val fromTo = entry.geoId.substring("C5600US".length)
          val fromGeoId = fromTo.substring(0, "ssccczzzzzzzz".length)
          val toGeoId = fromTo.substring("ssccczzzzzzzz".length)
          val mode = MeansOfTransportation(entry.lineNumber).get
          OD(fromGeoId, toGeoId, mode, entry.estimate)
        }
    }
  }
object MeansOfTransportationTableReader {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Provide the path to the data folder CTPP")
    val pathToData = args(0)
    val ods = new MeansOfTransportationTableReader(PathToData(pathToData)).read()
    println(s"Read ${ods.size} OD pairs")
  }
}