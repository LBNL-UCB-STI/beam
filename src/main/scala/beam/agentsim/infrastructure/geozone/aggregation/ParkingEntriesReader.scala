package beam.agentsim.infrastructure.geozone.aggregation

import java.io.Closeable
import java.nio.file.Path

import scala.reflect.ClassTag

import beam.agentsim.infrastructure.geozone.H3Index
import beam.utils.csv.GenericCsvReader

class ParkingEntriesReader[T](
  parkingFile: Path,
  parkingEntryMapper: java.util.Map[String, String] => ParkingEntry[T]
)(implicit classTag: ClassTag[ParkingEntry[T]]) {

  def readParkingEntries(): Seq[ParkingEntry[T]] = {
    val (iter: Iterator[ParkingEntry[T]], toClose: Closeable) =
      GenericCsvReader
        .readAs[ParkingEntry[T]](parkingFile.toString, parkingEntryMapper, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

}

object ParkingEntriesReader {

  def tazReader(parkingFile: Path): ParkingEntriesReader[TazCoordinate] = {
    new ParkingEntriesReader(parkingFile, toTazParkingEntry)
  }

  def geoIndexReader(parkingFile: Path): ParkingEntriesReader[H3Index] = {
    new ParkingEntriesReader(parkingFile, toH3IndexParkingEntry)
  }

  private def toTazParkingEntry(rec: java.util.Map[String, String]): ParkingEntry[TazCoordinate] = {
    CsvTazParkingEntry(
      taz = TazCoordinate(rec.get("taz")),
      parkingType = rec.get("parkingType"),
      pricingModel = rec.get("pricingModel"),
      chargingPointType = rec.get("chargingPointType"),
      reservedFor = rec.get("reservedFor"),
      numStalls = rec.get("numStalls").toLong,
      feeInCents = rec.get("feeInCents").toDouble
    )
  }

  private def toH3IndexParkingEntry(rec: java.util.Map[String, String]): ParkingEntry[H3Index] = {
    CsvH3IndexParkingEntry(
      geoIndex = H3Index(rec.get("geoIndex")),
      parkingType = rec.get("parkingType"),
      pricingModel = rec.get("pricingModel"),
      chargingPointType = rec.get("chargingPointType"),
      reservedFor = rec.get("reservedFor"),
      numStalls = rec.get("numStalls").toLong,
      feeInCents = rec.get("feeInCents").toDouble
    )
  }
}
