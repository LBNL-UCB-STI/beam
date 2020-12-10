package beam.agentsim.infrastructure.geozone.aggregation

import java.io.Closeable
import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.{H3Index, WgsCoordinate}
import beam.utils.csv.GenericCsvReader

object H3IndexFileReader {
  case class CenterEntry(id: String, wgsCoordinate: WgsCoordinate, areaInM2: Double)

  def readIndexes(path: Path): Set[H3Index] = {
    readCenterEntries(path)
      .flatMap(entry => H3Index.tryCreate(entry.id))
      .toSet
  }

  def readCenterEntries(path: Path): Seq[CenterEntry] = {
    val (iter: Iterator[CenterEntry], toClose: Closeable) =
      GenericCsvReader.readAs[CenterEntry](path.toString, toCenterEntry, _ => true)
    try {
      iter.toList
    } finally {
      toClose.close()
    }
  }

  private def toCenterEntry(rec: java.util.Map[String, String]): CenterEntry = {
    CenterEntry(
      id = rec.get("geoIndex"),
      wgsCoordinate = WgsCoordinate(
        latitude = rec.get("coord-y").toDouble,
        longitude = rec.get("coord-x").toDouble
      ),
      areaInM2 = rec.get("areaInM2").toDouble
    )
  }

}
