package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.TestConfigUtils
import beam.utils.scenario.InputType
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, Paths}

class UrbansimReaderV2Test extends AnyWordSpec with Matchers {
  private val testDataPath = "test/test-resources/urbansim_v2"

  private val testGeoUtils = new GeoUtils {
    override def wgs2Utm(coord: org.matsim.api.core.v01.Coord): org.matsim.api.core.v01.Coord = coord
    override def localCRS: String = "epsg:26910" // Default UTM Zone 10N (San Francisco)
  }

  "UrbansimReaderV2" should {
    "read CSV files successfully" in {
      // Create test data paths
      val csvPath = s"$testDataPath/csv"

      // Ensure test data exists
      val requiredFiles = List(
        s"$csvPath/households.csv.gz",
        s"$csvPath/persons.csv.gz",
        s"$csvPath/plans.csv.gz",
        s"$csvPath/blocks.csv.gz"
      )

      // Skip test if test data is not available
      assume(
        requiredFiles.forall(path => Files.exists(Paths.get(path))),
        "Test data files not found. Please provide CSV test data."
      )

      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      val reader = new UrbansimReaderV2(
        inputPersonPath = s"$csvPath/persons.csv.gz",
        inputPlanPath = s"$csvPath/plans.csv.gz",
        inputHouseholdPath = s"$csvPath/households.csv.gz",
        inputVehiclePath = s"$csvPath/vehicles.csv.gz",
        inputBlockPath = s"$csvPath/blocks.csv.gz",
        geoUtils = testGeoUtils,
        shouldConvertWgs2Utm = false,
        modeMap = modeMap,
        fileFormat = "csv"
      )

      // Test reading persons
      val persons = reader.getPersons.toSeq
      persons should not be empty

      // Test reading plans
      val plans = reader.getPlans.toSeq
      plans should not be empty

      // Test reading households
      val households = reader.getHousehold.toSeq
      households should not be empty

      // Test reading vehicles (optional)
      val vehicles = reader.getVehicles
      // No assertion on vehicles as they are optional
    }

    "read Parquet files successfully when all required files exist" in {
      // Create test data paths
      val parquetPath = s"$testDataPath/parquet"

      // Ensure test data exists
      val requiredFiles = List(
        s"$parquetPath/households.parquet",
        s"$parquetPath/persons.parquet",
        s"$parquetPath/plans.parquet",
        s"$parquetPath/blocks.parquet"
      )

      // Skip test if test data is not available
      assume(
        requiredFiles.forall(path => Files.exists(Paths.get(path))),
        "Test data files not found. Please provide Parquet test data."
      )

      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      val reader = new UrbansimReaderV2(
        inputPersonPath = s"$parquetPath/persons.parquet",
        inputPlanPath = s"$parquetPath/plans.parquet",
        inputHouseholdPath = s"$parquetPath/households.parquet",
        inputVehiclePath = s"$parquetPath/vehicles.parquet",
        inputBlockPath = s"$parquetPath/blocks.parquet",
        geoUtils = testGeoUtils,
        shouldConvertWgs2Utm = false,
        modeMap = modeMap,
        fileFormat = "parquet"
      )

      // Test reading persons
      val persons = reader.getPersons.toSeq
      persons should not be empty

      // Test reading plans
      val plans = reader.getPlans.toSeq
      plans should not be empty

      // Test reading households
      val households = reader.getHousehold.toSeq
      households should not be empty

      // Test reading vehicles (optional)
      val vehicles = reader.getVehicles
      // No assertion on vehicles as they are optional
    }

    "throw an exception when Parquet files are missing" in {
      val parquetPath = s"$testDataPath/missing"
      val modeMap = Map("WALK" -> "walk", "CAR" -> "car", "TRANSIT" -> "transit")

      an[IllegalArgumentException] should be thrownBy {
        new UrbansimReaderV2(
          inputPersonPath = s"$parquetPath/persons.parquet",
          inputPlanPath = s"$parquetPath/plans.parquet",
          inputHouseholdPath = s"$parquetPath/households.parquet",
          inputVehiclePath = s"$parquetPath/vehicles.parquet",
          inputBlockPath = s"$parquetPath/blocks.parquet",
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = modeMap,
          fileFormat = "parquet"
        )
      }
    }
  }
}
