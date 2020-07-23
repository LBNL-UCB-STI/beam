package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.agentsim.infrastructure.taz.CsvTaz

private[aggregation] class TazCentersReader(tazCentersFile: Path) {

  def readTazToWgsCoordinate(): Map[TazCoordinate, WgsCoordinate] = {
    CsvTaz
      .readCsvFile(tazCentersFile.toString)
      .map { elem =>
        TazCoordinate(elem.id) -> WgsCoordinate(latitude = elem.coordY, longitude = elem.coordX)
      }
      .toMap
  }

}

private object TazCentersReader {
  case class TazAndCoordinate(taz: TazCoordinate, coordinate: WgsCoordinate)
}
