package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, HouseholdIncome, OD}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class HouseholdIncomeTableReader(pathToData: PathToData)
    extends BaseTableReader(pathToData, Table.HouseholdIncome, Some("C56")) {

  def read(): Seq[OD[HouseholdIncome]] = {
    CTPPParser
      .readTable(pathToCsvTable, x => geographyLevelFilter(x))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        val income = HouseholdIncome.all(entry.lineNumber - 1)
        OD(fromGeoId, toGeoId, income, entry.estimate)
      }
      .filter(_.attribute != HouseholdIncome.Total)
  }
}

object HouseholdIncomeTableReader {

  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Provide the path to the data folder CTPP")
    val pathToData = args(0)
    val ods = new HouseholdIncomeTableReader(PathToData(pathToData)).read()
    println(s"Read ${ods.size} OD pairs")
  }
}
