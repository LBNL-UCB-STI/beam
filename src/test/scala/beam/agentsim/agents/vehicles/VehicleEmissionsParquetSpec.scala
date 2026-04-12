package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile}
import beam.utils.BeamVehicleUtils.convertRecordStringToDoubleTypedRange
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path => NioPath}
import scala.jdk.CollectionConverters._

class VehicleEmissionsParquetSpec extends AnyFunSpecLike with Matchers with BeforeAndAfterAll {

  private var tempDir: NioPath = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    tempDir = Files.createTempDirectory("vehicle-emissions-parquet-spec")
  }

  override protected def afterAll(): Unit = {
    try deleteRecursively(tempDir)
    finally super.afterAll()
  }

  describe("EmissionsRateTableLoader") {
    it("should load RUNEX emissions rates from shared-store parquet files referenced by relative emfacId paths") {
      val relativeFile = "dataset/emfacId=post2014LDAGas/post2014LDAGas.parquet"
      val absoluteFile = tempDir.resolve(relativeFile)
      Files.createDirectories(absoluteFile.getParent)
      writeParquet(
        absoluteFile,
        Map(
          "speedMph_timeMin" -> 25.0,
          "county"           -> "alameda",
          "roadCategory"     -> "motorway",
          "process"          -> EmissionsProfile.RUNEX.toString,
          "co2_gram"         -> 321.5,
          "n2o_gram"         -> 1.25,
          "pm25_gram"        -> 0.75
        )
      )

      val csvParser = {
        val settings = new CsvParserSettings()
        settings.setHeaderExtractionEnabled(true)
        settings.detectFormatAutomatically()
        new CsvParser(settings)
      }

      val store = VehicleEmissions.EmissionsRateTableLoader.loadFromFile(
        IndexedSeq(tempDir.toString),
        relativeFile,
        csvParser
      )

      val speedBin = convertRecordStringToDoubleTypedRange("[22.5,27.5]")

      store("alameda")(EmissionsProfile.RUNEX.toString)("motorway")(speedBin) shouldBe
      Emissions(Map(Emissions.CO2 -> 321.5, Emissions.N2O -> 1.25, Emissions.PM25 -> 0.75))
    }

    it("should load PTOEX emissions rates from shared-store parquet files using speed bins") {
      val relativeFile = "dataset/emfacId=ptoHeavy/ptoHeavy.parquet"
      val absoluteFile = tempDir.resolve(relativeFile)
      Files.createDirectories(absoluteFile.getParent)
      writeParquet(
        absoluteFile,
        Map(
          "speedMph_timeMin" -> 20.0,
          "county"           -> "alameda",
          "process"          -> EmissionsProfile.PTOEX.toString,
          "co_gram"          -> 4.5,
          "bc_gram"          -> 0.2
        )
      )

      val csvParser = {
        val settings = new CsvParserSettings()
        settings.setHeaderExtractionEnabled(true)
        settings.detectFormatAutomatically()
        new CsvParser(settings)
      }

      val store = VehicleEmissions.EmissionsRateTableLoader.loadFromFile(
        IndexedSeq(tempDir.toString),
        relativeFile,
        csvParser
      )

      val speedBin = convertRecordStringToDoubleTypedRange("[17.5,22.5]")

      store("alameda")(EmissionsProfile.PTOEX.toString)("")(speedBin) shouldBe
      Emissions(Map(Emissions.CO -> 4.5, Emissions.BC -> 0.2))
    }
  }

  private def writeParquet(path: NioPath, row: Map[String, Any]): Unit = {
    val fields = row.toIndexedSeq.map { case (name, value) =>
      val schema = value match {
        case _: java.lang.Number => Schema.create(Schema.Type.DOUBLE)
        case _: Number           => Schema.create(Schema.Type.DOUBLE)
        case _                   => Schema.create(Schema.Type.STRING)
      }
      new Schema.Field(name, schema, "", null)
    }
    val schema = Schema.createRecord("EmissionsRateRow", "", "beam.agentsim.agents.vehicles", false, fields.asJava)
    val record = new GenericData.Record(schema)
    row.foreach { case (name, value) =>
      value match {
        case n: java.lang.Number => record.put(name, n.doubleValue())
        case n: Number           => record.put(name, n.doubleValue())
        case other               => record.put(name, other.toString)
      }
    }

    val outputFile = HadoopOutputFile.fromPath(new Path(path.toString), new Configuration())
    val writer = AvroParquetWriter
      .builder[GenericData.Record](outputFile)
      .withSchema(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()

    try writer.write(record)
    finally writer.close()
  }

  private def deleteRecursively(path: NioPath): Unit = {
    if (path == null || !Files.exists(path)) return
    Files.walk(path).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
  }
}
