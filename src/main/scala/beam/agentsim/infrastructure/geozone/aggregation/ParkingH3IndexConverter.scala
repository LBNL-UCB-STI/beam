package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.{GeoZone, H3Index, WgsCoordinate}
import beam.agentsim.infrastructure.geozone.aggregation.ParkingH3IndexConverter.{
  H3IndexParkingEntry,
  H3IndexParkingEntryGroup
}

class ParkingH3IndexConverter[T](
  parkingEntriesContainer: ParkingEntriesContainer[T],
  indexMapperBuilder: H3IndexMapperBuilder
) {

  def grouper(): GeoGrouper = {
    new GeoGrouper(convert())
  }

  private lazy val coordinatesToIndex: Map[WgsCoordinate, H3Index] = {
    val content = indexMapperBuilder
      .buildMapper(parkingEntriesContainer.wgsCoordinates)
      .generateContent()
    GeoZone.mapCoordinateToIndex(content)
  }

  lazy val idToH3Index: Map[T, H3Index] = {
    parkingEntriesContainer.parkingEntries.map { entry =>
      val wgsCoordinate = parkingEntriesContainer.wgsCoordinate(entry.id)
      entry.id -> coordinatesToIndex(wgsCoordinate)
    }.toMap
  }

  def convert(): Seq[H3IndexParkingEntry] = {
    val allElements: Seq[H3IndexParkingEntry] = parkingEntriesContainer.parkingEntries.map { entry =>
      H3IndexParkingEntry(
        group = H3IndexParkingEntryGroup(
          h3Index = idToH3Index(entry.id),
          parkingType = entry.parkingType,
          pricingModel = entry.pricingModel,
          chargingPointType = entry.chargingPointType,
          reservedFor = entry.reservedFor
        ),
        numStalls = entry.numStalls,
        feeInCents = entry.feeInCents
      )
    }
    allElements
  }
}

object ParkingH3IndexConverter {

  case class H3IndexParkingEntryGroup(
    h3Index: H3Index,
    parkingType: String,
    pricingModel: String,
    chargingPointType: String,
    reservedFor: String
  )

  case class H3IndexParkingEntry(
    group: H3IndexParkingEntryGroup,
    numStalls: Long,
    feeInCents: Double
  )

  def tazParkingToH3Index(
    tazParkingFile: Path,
    tazCentersFile: Path,
    targetCentersFile: Path
  ): ParkingH3IndexConverter[TazCoordinate] = {
    val targetIndexes = H3IndexFileReader.readIndexes(targetCentersFile)
    new ParkingH3IndexConverter(
      parkingEntriesContainer = ParkingEntriesContainer.fromTaz(tazParkingFile, tazCentersFile),
      indexMapperBuilder = H3IndexMapperBuilder.wgsCoordinate(targetIndexes.par)
    )
  }

  def geoIndexParkingToH3Index(
    geoIndexParkingFile: Path,
    targetCentersFile: Path
  ): ParkingH3IndexConverter[H3Index] = {
    val targetIndexes = H3IndexFileReader.readIndexes(targetCentersFile)
    val container = ParkingEntriesContainer.fromH3Index(geoIndexParkingFile)
    new ParkingH3IndexConverter(
      parkingEntriesContainer = container,
      indexMapperBuilder = H3IndexMapperBuilder.wgsCoordinate(targetIndexes.par)
    )
  }

}
