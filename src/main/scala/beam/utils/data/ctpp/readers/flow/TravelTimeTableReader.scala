package beam.utils.data.ctpp.readers.flow

import java.util.concurrent.TimeUnit

import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.CTPPParser
import beam.utils.data.ctpp.models.{FlowGeoParser, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData, Table}
import beam.utils.data.synthpop.models.Models.TazGeoId

class TravelTimeTableReader(
  dbInfo: CTPPDatabaseInfo,
  val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography
) extends BaseTableReader(dbInfo, Table.TravelTime, Some(residenceToWorkplaceFlowGeography.level)) {
  /*
    TableShell(B302106,3,2,Less than 5 minutes)
    TableShell(B302106,4,2,5 to 14 minutes)
    TableShell(B302106,5,2,15 to 19 minutes)
    TableShell(B302106,6,2,20 to 29 minutes)
    TableShell(B302106,7,2,30 to 44 minutes)
    TableShell(B302106,8,2,45 to 59 minutes)
    TableShell(B302106,9,2,60 to 74 minutes)
    TableShell(B302106,10,2,75 to 89 minutes)
    TableShell(B302106,11,2,90 minutes or more)
   */
  private val interestedLineNumber: Set[Int] = (3 to 11).toSet

  def read(): Iterable[OD[Range]] = {
    readRaw()
      .filter(x => interestedLineNumber.contains(x.lineNumber))
      .map { entry =>
        val (fromGeoId, toGeoId) = FlowGeoParser.parse(entry.geoId).get
        OD(fromGeoId, toGeoId, toRange(entry.lineNumber), entry.estimate)
      }
  }

  private def toRange(lineNumber: Int): Range = {
    val (start, end) = lineNumber match {
      case 3 =>
        // TableShell(B302106,3,2,Less than 5 minutes)
        val start = 0
        val end = TimeUnit.MINUTES.toSeconds(5).toInt
        (start, end)
      case 4 =>
        // TableShell(B302106,4,2,5 to 14 minutes)
        val start = TimeUnit.MINUTES.toSeconds(5).toInt
        val end = TimeUnit.MINUTES.toSeconds(14).toInt
        (start, end)
      case 5 =>
        // TableShell(B302106,5,2,15 to 19 minutes)
        val start = TimeUnit.MINUTES.toSeconds(15).toInt
        val end = TimeUnit.MINUTES.toSeconds(19).toInt
        (start, end)
      case 6 =>
        // TableShell(B302106,6,2,20 to 29 minutes)
        val start = TimeUnit.MINUTES.toSeconds(20).toInt
        val end = TimeUnit.MINUTES.toSeconds(29).toInt
        (start, end)
      case 7 =>
        // TableShell(B302106,7,2,30 to 44 minutes)
        val start = TimeUnit.MINUTES.toSeconds(30).toInt
        val end = TimeUnit.MINUTES.toSeconds(44).toInt
        (start, end)
      case 8 =>
        // TableShell(B302106,8,2,45 to 59 minutes)
        val start = TimeUnit.MINUTES.toSeconds(45).toInt
        val end = TimeUnit.MINUTES.toSeconds(59).toInt
        (start, end)
      case 9 =>
        // TableShell(B302106,9,2,60 to 74 minutes)
        val start = TimeUnit.MINUTES.toSeconds(60).toInt
        val end = TimeUnit.MINUTES.toSeconds(74).toInt
        (start, end)
      case 10 =>
        // TableShell(B302106,10,2,75 to 89 minutes)
        val start = TimeUnit.MINUTES.toSeconds(75).toInt
        val end = TimeUnit.MINUTES.toSeconds(89).toInt
        (start, end)
      case 11 =>
        // TableShell(B302106,11,2,90 minutes or more)
        val start = TimeUnit.MINUTES.toSeconds(90).toInt
        val end = TimeUnit.MINUTES.toSeconds(90).toInt
        (start, end)
    }
    Range.inclusive(start, end)
  }
}

object TravelTimeTableReader {

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData(" /mnt/data/work/beam/scenario-generation/cttp"), Set("34", "36"))

    val rdr = new TravelTimeTableReader(databaseInfo, ResidenceToWorkplaceFlowGeography.`State-County To State-County`)
    val readData = rdr.read().toVector

    val nonZeros = readData.filter(x => x.value != 0.0)
    val distinctHomeLocations = readData.map(_.source).distinct
    val distintWorkLocations = readData.map(_.destination).distinct
    val sumOfValues = readData.map(_.value).sum
    println(s"Read ${readData.size} OD pairs. ${nonZeros.size} is non-zero")
    println(s"distinctHomeLocations: ${distinctHomeLocations.size}")
    println(s"distintWorkLocations: ${distintWorkLocations.size}")
    println(s"sumOfValues: $sumOfValues")

    if (true) {
      val outputPath = "travelTimeTable_statePlace.csv"
      val writer =
        new CsvWriter(outputPath, IndexedSeq("src", "dest", "value"))
      readData.foreach { line =>
        writer.writeRow(IndexedSeq(line.source, line.destination, line.value))
      }
      writer.close()
      println(s"Read data written to $outputPath")
    }
  }
}
