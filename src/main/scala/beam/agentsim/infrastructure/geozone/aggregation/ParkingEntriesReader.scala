package beam.agentsim.infrastructure.geozone.aggregation

import java.io.Closeable
import java.nio.file.Path

import scala.reflect.ClassTag

import beam.agentsim.infrastructure.geozone.GeoIndex
import beam.utils.csv.GenericCsvReader

class ParkingEntriesReader[T](
  parkingFile: Path,
  parkingEntryMapper: java.util.Map[String, String] => ParkingEntry[T]
)(implicit t: ClassTag[T]) {

  def readParkingEntries(): Seq[ParkingEntry[T]] = {
    println(s"@@@@@@@ originalParkingFile: ${parkingFile.toString}")
    println(s"@@@@@@@ originalParkingFile.absolute: ${parkingFile.toAbsolutePath.toString}")
    val (iter: Iterator[ParkingEntry[T]], toClose: Closeable) =
      GenericCsvReader.readAs[ParkingEntry[T]](parkingFile.toString, parkingEntryMapper, _ => true)
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

  def geoIndexReader(parkingFile: Path): ParkingEntriesReader[GeoIndex] = {
    new ParkingEntriesReader(parkingFile, toGeoIndexParkingEntry)
  }

  private def toTazParkingEntry(rec: java.util.Map[String, String]): ParkingEntry[TazCoordinate] = {
    CsvTazParkingEntry(
      taz = TazCoordinate(rec.get("taz")),
      parkingType = rec.get("parkingType"),
      pricingModel = rec.get("pricingModel"),
      chargingType = rec.get("chargingType"),
      reservedFor = rec.get("reservedFor"),
      numStalls = rec.get("numStalls").toLong,
      feeInCents = rec.get("feeInCents").toDouble
    )
  }

  private def toGeoIndexParkingEntry(rec: java.util.Map[String, String]): ParkingEntry[GeoIndex] = {
    CsvGeoIndexParkingEntry(
      geoIndex = GeoIndex(rec.get("geoIndex")),
      parkingType = rec.get("parkingType"),
      pricingModel = rec.get("pricingModel"),
      chargingType = rec.get("chargingType"),
      reservedFor = rec.get("reservedFor"),
      numStalls = rec.get("numStalls").toLong,
      feeInCents = rec.get("feeInCents").toDouble
    )
  }
}
