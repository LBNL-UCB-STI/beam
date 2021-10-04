package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import scala.collection.parallel.ParSet

import beam.agentsim.infrastructure.geozone.{H3Index, H3Wrapper, WgsCoordinate}

class ParkingEntriesContainer[T](
  val parkingEntries: Seq[ParkingEntry[T]],
  private val idToCoordinates: Map[T, WgsCoordinate]
) {

  def wgsCoordinate(tazCoordinate: T): WgsCoordinate = idToCoordinates(tazCoordinate)

  private lazy val parkingTazToCoordinate: Map[T, WgsCoordinate] = {
    parkingEntries.flatMap { entry =>
      idToCoordinates
        .get(entry.id)
        .map(coordinate => entry.id -> coordinate)
    }.toMap
  }

  lazy val wgsCoordinates: ParSet[WgsCoordinate] = parkingTazToCoordinate.values.par.toSet

}

object ParkingEntriesContainer {

  def fromTaz(parkingFile: Path, centersFile: Path): ParkingEntriesContainer[TazCoordinate] = {
    val parkingEntries = ParkingEntriesReader.tazReader(parkingFile).readParkingEntries()
    val other: Map[TazCoordinate, WgsCoordinate] = new TazCentersReader(centersFile).readTazToWgsCoordinate()
    new ParkingEntriesContainer(parkingEntries, other)
  }

  def fromH3Index(parkingFile: Path): ParkingEntriesContainer[H3Index] = {
    val parkingEntries: Seq[ParkingEntry[H3Index]] =
      ParkingEntriesReader.geoIndexReader(parkingFile).readParkingEntries()

    val other: Map[H3Index, WgsCoordinate] = parkingEntries.map { entry =>
      entry.id -> H3Wrapper.wgsCoordinate(entry.id)
    }.toMap
    new ParkingEntriesContainer(parkingEntries, other)
  }

}
