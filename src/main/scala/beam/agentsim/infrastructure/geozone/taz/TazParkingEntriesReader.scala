package beam.agentsim.infrastructure.geozone.taz

import java.io.Closeable
import java.nio.file.Path

import beam.utils.csv.GenericCsvReader

class TazParkingEntriesReader(
  tazParkingFile: Path
) {

  def readWgsParkingEntries(): Seq[TazParkingEntry] = {
    val (iter: Iterator[TazParkingEntry], toClose: Closeable) =
      GenericCsvReader.readAs[TazParkingEntry](tazParkingFile.toString, toTazParkingEntry, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

  private def toTazParkingEntry(rec: java.util.Map[String, String]): TazParkingEntry = {
    TazParkingEntry(
      taz = TazCoordinate(rec.get("taz")),
      parkingType = rec.get("taz"),
      pricingModel = rec.get("pricingModel"),
      chargingType = rec.get("chargingType"),
      reservedFor = rec.get("reservedFor"),
      numStalls = rec.get("numStalls").toLong,
      feeInCents = rec.get("feeInCents").toDouble
    )
  }

}
