package beam.agentsim.infrastructure.taz

import java.io.Closeable

import beam.utils.csv.GenericCsvReader

case class CsvTaz(id: String, coordX: Double, coordY: Double, area: Double)

object CsvTaz {

  def readCsvFile(filePath: String): Seq[CsvTaz] = {
    val (iter: Iterator[CsvTaz], toClose: Closeable) =
      GenericCsvReader.readAs[CsvTaz](filePath, toCsvTaz, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

  private def toCsvTaz(rec: java.util.Map[String, String]): CsvTaz = {
    CsvTaz(
      id = rec.get("taz"),
      coordX = rec.get("coord-x").toDouble,
      coordY = rec.get("coord-y").toDouble,
      area = rec.get("area").toDouble
    )
  }

}
