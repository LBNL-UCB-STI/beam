package beam.agentsim.infrastructure.geozone.aggregation

import java.io.Closeable
import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.agentsim.infrastructure.geozone.aggregation.TazCentersReader.TazAndCoordinate
import beam.utils.csv.GenericCsvReader

private[aggregation] class TazCentersReader(
  tazCentersFile: Path,
) {

  def readTazToWgsCoordinate(): Map[TazCoordinate, WgsCoordinate] = {
    val (iter: Iterator[TazAndCoordinate], toClose: Closeable) =
      GenericCsvReader.readAs[TazAndCoordinate](tazCentersFile.toString, toWgsCoordinate, _ => true)
    try {
      iter.toSeq.map(v => v.taz -> v.coordinate).toMap
    } finally {
      toClose.close()
    }
  }

  private def toWgsCoordinate(rec: java.util.Map[String, String]): TazAndCoordinate = {
    TazAndCoordinate(
      taz = TazCoordinate(rec.get("taz")),
      coordinate = WgsCoordinate(
        latitude = rec.get("coord-y").toDouble,
        longitude = rec.get("coord-x").toDouble
      )
    )
  }

}

private object TazCentersReader {
  case class TazAndCoordinate(taz: TazCoordinate, coordinate: WgsCoordinate)
}
