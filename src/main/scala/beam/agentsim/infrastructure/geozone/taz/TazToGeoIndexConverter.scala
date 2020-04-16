package beam.agentsim.infrastructure.geozone.taz

import beam.agentsim.infrastructure.geozone.{GeoIndex, GeoZone, WgsCoordinate}
import beam.agentsim.infrastructure.geozone.taz.TazToGeoIndexConverter.{GeoIndexParkingEntry, GeoIndexParkingEntryGroup}

class TazToGeoIndexConverter(
  tazToWgsCoordinateMapper: TazToWgsCoordinateMapper,
  geoZoneHexBuilder: GeoZoneHexGeneratorBuilder
) {

  def grouper(): GeoGrouper = {
    new GeoGrouper(convert())
  }

  lazy val tazToGeoIndex: Map[TazCoordinate, GeoIndex] = {
    val coordinatesToIndex: Map[WgsCoordinate, GeoIndex] = {
      val content = geoZoneHexBuilder
        .buildHexZoneGenerator(tazToWgsCoordinateMapper.wgsCoordinates)
        .generateContent()
      GeoZone.mapCoordinateToIndex(content)
    }
    tazToWgsCoordinateMapper.parkingEntries.map { entry: TazParkingEntry =>
      val wgsCoordinate = tazToWgsCoordinateMapper.wgsCoordinate(entry.taz)
      entry.taz -> coordinatesToIndex(wgsCoordinate)
    }.toMap
  }

  def convert(): Seq[GeoIndexParkingEntry] = {
    val allElements: Seq[GeoIndexParkingEntry] = tazToWgsCoordinateMapper.parkingEntries.map { entry: TazParkingEntry =>
      GeoIndexParkingEntry(
        group = GeoIndexParkingEntryGroup(
          geoIndex = tazToGeoIndex(entry.taz),
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

object TazToGeoIndexConverter {

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

}
