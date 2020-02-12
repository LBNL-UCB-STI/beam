package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, HouseholdIncome, OD}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class HouseholdIncomeTableReader(pathToData: PathToData)
    extends BaseTableReader(pathToData, Table.HouseholdIncomeInThePast12Months, Some("C56")) {

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
    val readData = new HouseholdIncomeTableReader(PathToData(pathToData)).read()
    val nonZeros = readData.filter(x => x.value != 0.0)
    val distinctHomeLocations = readData.map(_.source).distinct.size
    val distintWorkLocations = readData.map(_.destination).distinct.size
    val sumOfValues = readData.map(_.value).sum
    println(s"Read ${readData.size} OD pairs. ${nonZeros.size} is non-zero")
    println(s"distinctHomeLocations: $distinctHomeLocations")
    println(s"distintWorkLocations: $distintWorkLocations")
    println(s"sumOfValues: $sumOfValues")
  }
}
