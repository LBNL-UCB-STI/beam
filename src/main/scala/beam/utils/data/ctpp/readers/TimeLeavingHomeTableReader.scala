package beam.utils.data.ctpp.readers

import java.util.concurrent.TimeUnit

import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, OD, ResidenceGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{PathToData, Table}

class TimeLeavingHomeTableReader(pathToData: PathToData)
    extends BaseTableReader(pathToData, Table.TimeLeavingHome, Some("C56")) {
  /*
    TableShell(B302104,1,0,Total)
    TableShell(B302104,2,1,Did not work at home:)
    TableShell(B302104,3,2,5:00 a.m. to  5:29 a.m.)
    TableShell(B302104,4,2,5:30 a.m. to  5:59 a.m.)
    TableShell(B302104,5,2,6:00 a.m. to  6:29 a.m.)
    TableShell(B302104,6,2,6:30 a.m. to  6:59 a.m.)
    TableShell(B302104,7,2,7:00 a.m. to  7:29 a.m.)
    TableShell(B302104,8,2,7:30 a.m. to 7:59 a.m.)
    TableShell(B302104,9,2,8:00 a.m. to  8:29 a.m.)
    TableShell(B302104,10,2,8:30 a.m. to 8:59 a.m.)
    TableShell(B302104,11,2,9:00 a.m. to  9:59 a.m.)
    TableShell(B302104,12,2,10:00 a.m. to 10:59 a.m.)
    TableShell(B302104,13,2,11:00 a.m. to 11:59 a.m.)
    TableShell(B302104,14,2,12:00 p.m. to  3:59 p.m.)
    TableShell(B302104,15,2,4:00 p.m. to 11:59 p.m.)
    TableShell(B302104,16,2,12:00 a.m. to 4:59 a.m.)
    TableShell(B302104,17,1,Worked at home)
   */
  private val interestedLineNumber: Set[Int] = (3 to 16).toSet

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
      case 16 =>
        val start = TimeUnit.HOURS.toSeconds(0).toInt
        val end = (TimeUnit.HOURS.toSeconds(4) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 3 =>
        val start = TimeUnit.HOURS.toSeconds(5).toInt
        val end = (TimeUnit.HOURS.toSeconds(5) + TimeUnit.MINUTES.toSeconds(29)).toInt
        (start, end)
      case 4 =>
        val start = (TimeUnit.HOURS.toSeconds(5) + TimeUnit.MINUTES.toSeconds(30)).toInt
        val end = (TimeUnit.HOURS.toSeconds(5) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 5 =>
        val start = TimeUnit.HOURS.toSeconds(6).toInt
        val end = (TimeUnit.HOURS.toSeconds(6) + TimeUnit.MINUTES.toSeconds(29)).toInt
        (start, end)
      case 6 =>
        val start = (TimeUnit.HOURS.toSeconds(6) + TimeUnit.MINUTES.toSeconds(30)).toInt
        val end = (TimeUnit.HOURS.toSeconds(6) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 7 =>
        val start = TimeUnit.HOURS.toSeconds(7).toInt
        val end = (TimeUnit.HOURS.toSeconds(7) + TimeUnit.MINUTES.toSeconds(29)).toInt
        (start, end)
      case 8 =>
        val start = (TimeUnit.HOURS.toSeconds(7) + TimeUnit.MINUTES.toSeconds(30)).toInt
        val end = (TimeUnit.HOURS.toSeconds(7) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 9 =>
        val start = TimeUnit.HOURS.toSeconds(8).toInt
        val end = (TimeUnit.HOURS.toSeconds(8) + TimeUnit.MINUTES.toSeconds(29)).toInt
        (start, end)
      case 10 =>
        val start = (TimeUnit.HOURS.toSeconds(8) + TimeUnit.MINUTES.toSeconds(30)).toInt
        val end = (TimeUnit.HOURS.toSeconds(8) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 11 =>
        val start = TimeUnit.HOURS.toSeconds(9).toInt
        val end = (TimeUnit.HOURS.toSeconds(9) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 12 =>
        val start = TimeUnit.HOURS.toSeconds(10).toInt
        val end = (TimeUnit.HOURS.toSeconds(10) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 13 =>
        val start = TimeUnit.HOURS.toSeconds(11).toInt
        val end = (TimeUnit.HOURS.toSeconds(11) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 14 =>
        val start = TimeUnit.HOURS.toSeconds(12).toInt
        val end = (TimeUnit.HOURS.toSeconds(15) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
      case 15 =>
        val start = TimeUnit.HOURS.toSeconds(16).toInt
        val end = (TimeUnit.HOURS.toSeconds(23) + TimeUnit.MINUTES.toSeconds(59)).toInt
        (start, end)
    }
    Range.inclusive(start, end)
  }
}

object TimeLeavingHomeTableReader {

  def main(args: Array[String]): Unit = {
    val timeLeavingHomeReader = new TimeLeavingHomeTableReader(
      PathToData("D:/Work/beam/Austin/2012-2016 CTPP documentation/tx/48")
    )
    val readData = timeLeavingHomeReader.read()

    val nonZeros = readData.filter(x => x.value != 0.0)
    val sum = readData.map(_.value).sum
    println(s"Read ${readData.size} OD pairs. ${nonZeros.size} is non-zero")
    println(s"Sum: $sum")
  }
}
