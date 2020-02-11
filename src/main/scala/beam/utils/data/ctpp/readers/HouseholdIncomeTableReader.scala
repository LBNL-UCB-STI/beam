package beam.utils.data.ctpp.readers

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, OD}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class HouseholdIncomeTableReader(pathToData: PathToData)
    extends BaseTableReader(pathToData, Table.HouseholdIncomeInThePast12Months, Some("C56")) {
  /*
  TableShell(B303100,1,0,Total)
  TableShell(B303100,2,1,Less than $15,000)
  TableShell(B303100,3,1,$15,000-$24,999)
  TableShell(B303100,4,1,$25,000-$34,999)
  TableShell(B303100,5,1,$35,000-$49,999)
  TableShell(B303100,6,1,$50,000-$74,999)
  TableShell(B303100,7,1,$75,000-$99,999)
  TableShell(B303100,8,1,$100,000-$149,999)
  TableShell(B303100,9,1,$150,000 or more)
   */
  private val interestedLineNumber: Set[Int] = (2 to 9).toSet

  def read(): Seq[OD[Range]] = {
    CTPPParser
      .readTable(pathToCsvTable, x => geographyLevelFilter(x) && interestedLineNumber.contains(x.lineNumber))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        OD(fromGeoId, toGeoId, toRange(entry.lineNumber), entry.estimate)
      }
  }
  private def toRange(lineNumber: Int): Range = {
    val (start, end) = lineNumber match {
      case 2 =>
        val start = 0
        val end = 14999
        (start, end)
      case 3 =>
        val start = 15000
        val end = 24999
        (start, end)
      case 4 =>
        val start = 25000
        val end = 34999
        (start, end)
      case 5 =>
        val start = 35000
        val end = 49999
        (start, end)
      case 6 =>
        val start = 50000
        val end = 74999
        (start, end)
      case 7 =>
        val start = 75000
        val end = 99999
        (start, end)
      case 8 =>
        val start = 100000
        val end = 149999
        (start, end)
      case 9 =>
        val start = 150000
        val end = 150000
        (start, end)
    }
    Range.inclusive(start, end)
  }
}

object HouseholdIncomeInThePast12Months {

  def main(args: Array[String]): Unit = {
    val rdr = new HouseholdIncomeTableReader(
      PathToData("D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48")
    )
    val readData = rdr.read()

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
