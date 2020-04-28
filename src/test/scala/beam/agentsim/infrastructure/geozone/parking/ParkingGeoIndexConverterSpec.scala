package beam.agentsim.infrastructure.geozone.parking

import java.nio.file.{Path, Paths}

import beam.agentsim.infrastructure.geozone.aggregation._
import beam.agentsim.infrastructure.geozone.aggregation.ParkingGeoIndexConverter.GeoIndexParkingEntryGroup
import beam.agentsim.infrastructure.geozone.{GeoIndex, H3Wrapper}
import org.scalatest.{Matchers, WordSpec}

class ParkingGeoIndexConverterSpec extends WordSpec with Matchers {

  "ParkingGeoIndexConverterSpec" should {

    "convert Taz coordinates to GeoIndex accordingly to csv file" in {
      val parkingFile: Path = Paths.get("test/input/geozone/parking/taz-parking.csv")
      val centersFile = Paths.get("test/input/geozone/parking/taz-centers.csv")
      val targetCenters = Paths.get("test/input/geozone/parking/target-centers.csv")

      val converter: ParkingGeoIndexConverter[TazCoordinate] = ParkingGeoIndexConverter.tazParkingToGeoIndex(
        tazParkingFile = parkingFile,
        tazCentersFile = centersFile,
        targetCentersFile = targetCenters
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

    "convert geoIndex-parking coordinates to GeoIndex accordingly to csv file" in {
      val parkingFile: Path = Paths.get("test/input/geozone/parking/geoIndex-parking2.csv")
      val targetCenters = Paths.get("test/input/geozone/parking/target-centers.csv")

      val converter = ParkingGeoIndexConverter.geoIndexParkingToGeoIndex(
        geoIndexParkingFile = parkingFile,
        targetCentersFile = targetCenters
      )
      val grouper: GeoGrouper = converter
        .grouper()
        .aggregate(ValueAggregator.StallSummationAndFeeWeightAvg)
      val expectedValue = ParkingEntryValues(
        numStalls = 5,
        feeInCents = 18D
      )
      assertResult(Seq(expectedValue)) {
        val entryGroup = GeoIndexParkingEntryGroup(
          geoIndex = GeoIndex("820eb7fffffffff"),
          parkingType = "100821",
          pricingModel = "Block",
          chargingType = "DCFast(50|DC)",
          reservedFor = "Any"
        )
        grouper.groupValues(entryGroup)
      }
    }
  }

}
