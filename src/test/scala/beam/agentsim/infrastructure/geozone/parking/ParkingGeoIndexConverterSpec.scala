package beam.agentsim.infrastructure.geozone.parking

import java.nio.file.{Files, Path, Paths}

import beam.agentsim.infrastructure.geozone.aggregation._
import beam.agentsim.infrastructure.geozone.aggregation.ParkingGeoIndexConverter.GeoIndexParkingEntryGroup
import beam.agentsim.infrastructure.geozone.{GeoIndex, GeoIndexMapper, H3Wrapper}
import org.scalatest.{Matchers, WordSpec}

class ParkingGeoIndexConverterSpec extends WordSpec with Matchers {

  "ParkingGeoIndexConverter" should {

    "convert Taz coordinates to GeoIndex accordingly to csv file" in {
      val tazParkingFile: Path = Paths.get("test/input/geozone/parking/taz-parking.csv")
      println(s"@@@@@@@@@ tazParkingFile: ${Files.isRegularFile(tazParkingFile)}")
      val tazCentersFile = Paths.get("test/input/geozone/parking/taz-centers.csv")
      println(s"@@@@@@@@@ tazCentersFile: ${Files.isRegularFile(tazCentersFile)}")
      val targetCentersFile = Paths.get("test/input/geozone/parking/target-centers.csv")
      println(s"@@@@@@@@@ targetCentersFile: ${Files.isRegularFile(targetCentersFile)}")

      val converter: ParkingGeoIndexConverter[TazCoordinate] = ParkingGeoIndexConverter.tazParkingToGeoIndex(
        tazParkingFile = tazParkingFile,
        tazCentersFile = tazCentersFile,
        targetCentersFile = targetCentersFile
      )
      val grouper: GeoGrouper = converter
        .grouper()
        .aggregate(ValueAggregator.StallSummationAndFeeWeightAvg)

      val expectedValue = ParkingEntryValues(
        numStalls = 50,
        feeInCents = 16D
      )
      assertResult(Seq(expectedValue)) {
        val entryGroup = GeoIndexParkingEntryGroup(
          geoIndex = GeoIndex("82bc27fffffffff"),
          parkingType = "Workplace",
          pricingModel = "Block",
          chargingType = "DCFast(50|DC)",
          reservedFor = "Any"
        )
        grouper.groupValues(entryGroup)
      }
    }

    "convert geoIndex-parking coordinates to GeoIndex accordingly to csv file" in {
      val parkingFile: Path = Paths.get("test/input/geozone/parking/geoIndex-parking.csv")
      val targetCenters = Paths.get("test/input/geozone/parking/target-centers.csv")

      val converter = ParkingGeoIndexConverter. geoIndexParkingToGeoIndex(
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
