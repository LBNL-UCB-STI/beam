package beam.agentsim.infrastructure.geozone.parking

import java.nio.file.{Files, Path, Paths}

import beam.agentsim.infrastructure.geozone.aggregation._
import beam.agentsim.infrastructure.geozone.aggregation.ParkingH3IndexConverter.H3IndexParkingEntryGroup
import beam.agentsim.infrastructure.geozone.{H3Index, H3IndexMapper, H3Wrapper}
import org.scalatest.{Matchers, WordSpec}

class ParkingH3IndexConverterSpec extends WordSpec with Matchers {

  "ParkingH3IndexConverter" should {

    "convert Taz coordinates to H3Index accordingly to csv file" in {
      val tazParkingFile: Path = Paths.get("test/input/geozone/parking/taz-parking.csv")
      val tazCentersFile = Paths.get("test/input/geozone/parking/taz-centers.csv")
      val targetCentersFile = Paths.get("test/input/geozone/parking/target-centers.csv")

      val converter: ParkingH3IndexConverter[TazCoordinate] = ParkingH3IndexConverter.tazParkingToH3Index(
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
        val entryGroup = H3IndexParkingEntryGroup(
          h3Index = H3Index("82bc27fffffffff"),
          parkingType = "Workplace",
          pricingModel = "Block",
          chargingType = "DCFast(50|DC)",
          reservedFor = "Any"
        )
        grouper.groupValues(entryGroup)
      }
    }

    "convert geoIndex-parking coordinates to H3Index accordingly to csv file" in {
      val parkingFile: Path = Paths.get("test/input/geozone/parking/geoIndex-parking.csv")
      val targetCenters = Paths.get("test/input/geozone/parking/target-centers.csv")

      val converter = ParkingH3IndexConverter.geoIndexParkingToH3Index(
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
        val entryGroup = H3IndexParkingEntryGroup(
          h3Index = H3Index("820eb7fffffffff"),
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
