package beam.agentsim.infrastructure.geozone.aggregation

import java.nio.file.Path

import beam.agentsim.infrastructure.geozone.{GeoIndex, GeoZone, WgsCoordinate}
import beam.agentsim.infrastructure.geozone.aggregation.ParkingGeoIndexConverter.{
  GeoIndexParkingEntry,
  GeoIndexParkingEntryGroup
}

class ParkingGeoIndexConverter[T](
  parkingEntriesContainer: ParkingEntriesContainer[T],
  indexMapperBuilder: GeoIndexMapperBuilder
) {

  def grouper(): GeoGrouper = {
    new GeoGrouper(convert())
  }

  private lazy val coordinatesToIndex: Map[WgsCoordinate, GeoIndex] = {
    val content = indexMapperBuilder
      .buildMapper(parkingEntriesContainer.wgsCoordinates)
      .generateContent()
    GeoZone.mapCoordinateToIndex(content)
  }

  lazy val idToGeoIndex: Map[T, GeoIndex] = {
    parkingEntriesContainer.parkingEntries.map { entry =>
      val wgsCoordinate = parkingEntriesContainer.wgsCoordinate(entry.id)
      entry.id -> coordinatesToIndex(wgsCoordinate)
    }.toMap
  }

  def convert(): Seq[GeoIndexParkingEntry] = {
    val allElements: Seq[GeoIndexParkingEntry] = parkingEntriesContainer.parkingEntries.map { entry =>
      GeoIndexParkingEntry(
        group = GeoIndexParkingEntryGroup(
          geoIndex = idToGeoIndex(entry.id),
          parkingType = entry.parkingType,
          pricingModel = entry.pricingModel,
          chargingType = entry.chargingType,
          reservedFor = entry.reservedFor
        ),
        numStalls = entry.numStalls,
        feeInCents = entry.feeInCents
      )
    }
    allElements
  }
}

object ParkingGeoIndexConverter {

  case class GeoIndexParkingEntryGroup(
    geoIndex: GeoIndex,
    parkingType: String,
    pricingModel: String,
    chargingType: String,
    reservedFor: String,
  )

  case class GeoIndexParkingEntry(
    group: GeoIndexParkingEntryGroup,
    numStalls: Long,
    feeInCents: Double,
  )

  def tazParkingToGeoIndex(
    tazParkingFile: Path,
    tazCentersFile: Path,
    targetCentersFile: Path
  ): ParkingGeoIndexConverter[TazCoordinate] = {
    val targetIndexes = GeoIndexFileReader.readIndexes(targetCentersFile)
    new ParkingGeoIndexConverter(
      parkingEntriesContainer = ParkingEntriesContainer.fromTaz(tazParkingFile, tazCentersFile),
      indexMapperBuilder = GeoIndexMapperBuilder.wgsCoordinate(targetIndexes.par)
    )
  }

  def geoIndexParkingToGeoIndex(
    geoIndexParkingFile: Path,
    targetCentersFile: Path
  ): ParkingGeoIndexConverter[GeoIndex] = {
    val targetIndexes = GeoIndexFileReader.readIndexes(targetCentersFile)
    val container = ParkingEntriesContainer.fromGeoIndex(geoIndexParkingFile)
    new ParkingGeoIndexConverter(
      parkingEntriesContainer = container,
      indexMapperBuilder = GeoIndexMapperBuilder.wgsCoordinate(targetIndexes.par)
    )
  }

}
