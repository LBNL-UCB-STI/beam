package beam.agentsim.infrastructure.geozone.taz

import java.nio.file.Path

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.WgsCoordinate

class TazToWgsCoordinateMapper(
  val parkingEntries: Seq[TazParkingEntry],
  private val tazToCoordinates: Map[TazCoordinate, WgsCoordinate]
) {

  def wgsCoordinate(tazCoordinate: TazCoordinate): WgsCoordinate = tazToCoordinates(tazCoordinate)

  private lazy val parkingTazToCoordinate: Map[TazCoordinate, WgsCoordinate] = {
    parkingEntries.flatMap { entry =>
      tazToCoordinates
        .get(entry.taz)
        .map(coordinate => entry.taz -> coordinate)
    }.toMap
  }

  def wgsCoordinates: ParSet[WgsCoordinate] = parkingTazToCoordinate.values.par.toSet

}

object TazToWgsCoordinateMapper {

  def fromFiles(parkingFile: Path, centersFile: Path): TazToWgsCoordinateMapper = {
    val parkingEntries: Seq[TazParkingEntry] = new TazParkingEntriesReader(parkingFile).readWgsParkingEntries()
    val other: Map[TazCoordinate, WgsCoordinate] = new TazCentersReader(centersFile).readTazToWgsCoordinate()
    new TazToWgsCoordinateMapper(parkingEntries, other)
  }

}
