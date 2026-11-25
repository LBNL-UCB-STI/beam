package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.scenario.VehicleInfo
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.hadoop.conf.Configuration
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

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

    "read scenario-folder vehicle files by their extension" in {
      val directory = Files.createTempDirectory("urbansim-v2-vehicles")
      try {
        val householdPath = directory.resolve("households.csv")
        Files.write(
          householdPath,
          "household_id,cars,income,block_id\n".getBytes(StandardCharsets.UTF_8)
        )

        val parquetVehiclePath = directory.resolve("vehicles.parquet")
        writeVehicleParquet(parquetVehiclePath)
        val csvVehiclePath = directory.resolve("vehicles.csv")
        Files.write(
          csvVehiclePath,
          "vehicleId,vehicleTypeId,stateOfCharge,householdId\nveh-csv,sedan,0.5,hh-1\n".getBytes(StandardCharsets.UTF_8)
        )

        def reader(vehiclePath: Path) = new UrbansimReaderV2(
          inputPersonPath = directory.resolve("persons.csv").toString,
          inputPlanPath = directory.resolve("plans.csv").toString,
          inputHouseholdPath = householdPath.toString,
          inputVehiclePath = vehiclePath.toString,
          inputBlockPath = directory.resolve("blocks.csv").toString,
          geoUtils = testGeoUtils,
          shouldConvertWgs2Utm = false,
          modeMap = Map.empty,
          fileFormat = "csv"
        )

        reader(parquetVehiclePath).getVehicles.toSeq shouldBe Seq(
          VehicleInfo("veh-parquet", "sedan", Some(0.75), "hh-1")
        )
        reader(csvVehiclePath).getVehicles.toSeq shouldBe Seq(
          VehicleInfo("veh-csv", "sedan", Some(0.5), "hh-1")
        )
      } finally {
        val paths = Files.walk(directory)
        try paths.iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
        finally paths.close()
      }
    }
  }

  private def writeVehicleParquet(path: Path): Unit = {
    val fields = List("vehicleId", "vehicleTypeId", "householdId").map { name =>
      new Schema.Field(name, Schema.create(Schema.Type.STRING), "", null)
    } :+ new Schema.Field(
      "stateOfCharge",
      Schema.create(Schema.Type.DOUBLE),
      "",
      null
    )
    val schema = Schema.createRecord("Vehicle", "", "beam", false, fields.asJava)
    val outputFile = HadoopOutputFile.fromPath(new org.apache.hadoop.fs.Path(path.toUri), new Configuration())
    val writer = AvroParquetWriter
      .builder[GenericRecord](outputFile)
      .withSchema(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()

    try {
      val record = new GenericData.Record(schema)
      record.put("vehicleId", "veh-parquet")
      record.put("vehicleTypeId", "sedan")
      record.put("stateOfCharge", 0.75d)
      record.put("householdId", "hh-1")
      writer.write(record)
    } finally writer.close()
  }
}
