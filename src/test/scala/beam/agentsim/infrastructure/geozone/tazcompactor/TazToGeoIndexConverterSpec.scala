package beam.agentsim.infrastructure.geozone.tazcompactor

import java.nio.file.{Path, Paths}

import beam.agentsim.infrastructure.geozone.taz._
import beam.agentsim.infrastructure.geozone.taz.TazToGeoIndexConverter.GeoIndexParkingEntryGroup
import beam.agentsim.infrastructure.geozone.GeoIndex
import org.scalatest.{Matchers, WordSpec}

class TazToGeoIndexConverterSpec extends WordSpec with Matchers {

  "TazToGeoIndexConverter" should {
    "convert Taz coordinates to GeoIndex accordingly to csv file" in {
      val parkingFile: Path = Paths.get("test/input/geozone/tazcompactor/taz-parking.csv")
      val centersFile = Paths.get("test/input/geozone/tazcompactor/taz-centers.csv")

      val tazToWgsCoordinateMapper: TazToWgsCoordinateMapper = TazToWgsCoordinateMapper
        .fromFiles(parkingFile, centersFile)

      val converter = new TazToGeoIndexConverter(
        tazToWgsCoordinateMapper = tazToWgsCoordinateMapper,
        geoZoneHexBuilder = GeoZoneHexGeneratorBuilder.topDownEqualDemands(
          bucketsReduceFactor = 0.3,
          initialResolution = 2
        )
      )
      val grouper: GeoGrouper = converter
        .grouper()
        .aggregate(ValueAggregator.StallSummationAndFeeWeightAvg)

      val expectedValue = ParkingEntryValues(
        numStalls = 2,
        feeInCents = 0D
      )
      assertResult(Seq(expectedValue)) {
        val entryGroup = GeoIndexParkingEntryGroup(
          geoIndex = GeoIndex("82bc27fffffffff"),
          parkingType = "100653",
          pricingModel = "Block",
          chargingType = "DCFast(50|DC)",
          reservedFor = "Any"
        )
        grouper.groupValues(entryGroup)
      }
    }
  }

}
