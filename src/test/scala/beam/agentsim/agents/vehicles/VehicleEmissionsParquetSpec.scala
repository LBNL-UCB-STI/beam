package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile.EmissionsProcess
import beam.agentsim.agents.vehicles.VehicleEmissions.{Emissions, EmissionsProfile, EmissionsRateFilterStore}
import beam.sim.common.DoubleTypedRange
import beam.utils.BeamVehicleUtils.convertRecordStringToDoubleTypedRange
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.hadoop.util.HadoopOutputFile
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path => NioPath}
import scala.jdk.CollectionConverters._

class VehicleEmissionsParquetSpec extends AnyFunSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {

  private var tempDir: NioPath = _
  private val county: String = "alameda"
  private val roadCategory = "motorway"

  private val allPollutants: Map[String, Double] = Map(
    "ch4_gram"  -> 1.0,
    "co_gram"   -> 2.0,
    "co2_gram"  -> 3.0,
    "hc_gram"   -> 4.0,
    "nh3_gram"  -> 5.0,
    "n2o_gram"  -> 6.0,
    "nox_gram"  -> 7.0,
    "pm_gram"   -> 8.0,
    "pm10_gram" -> 9.0,
    "pm25_gram" -> 10.0,
    "rog_gram"  -> 11.0,
    "sox_gram"  -> 12.0,
    "tog_gram"  -> 13.0,
    "bc_gram"   -> 14.0
  )

  private val expectedEmissions = Emissions(
    Emissions.CH4  -> 1.0,
    Emissions.CO   -> 2.0,
    Emissions.CO2  -> 3.0,
    Emissions.HC   -> 4.0,
    Emissions.NH3  -> 5.0,
    Emissions.N2O  -> 6.0,
    Emissions.NOx  -> 7.0,
    Emissions.PM   -> 8.0,
    Emissions.PM10 -> 9.0,
    Emissions.PM25 -> 10.0,
    Emissions.ROG  -> 11.0,
    Emissions.SOx  -> 12.0,
    Emissions.TOG  -> 13.0,
    Emissions.BC   -> 14.0
  )

  private val processActivityValues: Vector[(EmissionsProfile.EmissionsProcess, Double)] = Vector(
    EmissionsProfile.RUNEX   -> 25.0,
    EmissionsProfile.IDLEX   -> 0.0,
    EmissionsProfile.STREX   -> 1.0,
    EmissionsProfile.HOTSOAK -> 2.0,
    EmissionsProfile.DIURN   -> 3.0,
    EmissionsProfile.RUNLOSS -> 4.0,
    EmissionsProfile.PMTW    -> 35.0,
    EmissionsProfile.PMBW    -> 45.0,
    EmissionsProfile.PRDUST  -> 55.0,
    EmissionsProfile.PTOEX   -> 65.0
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    tempDir = Files.createTempDirectory("vehicle-emissions-parquet-spec")
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    VehicleEmissions.Emissions.filter = None
  }

  override protected def afterAll(): Unit = {
    try deleteRecursively(tempDir)
    finally super.afterAll()
  }

  describe("EmissionsRateTableLoader") {
    it("should load all pollutants for every process from parquet files") {
      val relativeFile = "dataset/all-processes/emissions.parquet"
      val absoluteFile = tempDir.resolve(relativeFile)
      Files.createDirectories(absoluteFile.getParent)
      writeParquet(absoluteFile, processRows)

      val store = VehicleEmissions.EmissionsRateTableLoader.loadFromFile(
        IndexedSeq(tempDir.toString),
        relativeFile,
        csvParser
      )

      assertStore(store)
    }

    it("should load all pollutants for every process from csv files") {
      val relativeFile = "dataset/all-processes/emissions.csv"
      val absoluteFile = tempDir.resolve(relativeFile)
      Files.createDirectories(absoluteFile.getParent)
      writeCsv(absoluteFile, processRows)

      val store = VehicleEmissions.EmissionsRateTableLoader.loadFromFile(
        IndexedSeq(tempDir.toString),
        relativeFile,
        csvParser
      )

      assertStore(store)
    }
  }

  private def csvParser: CsvParser = {
    val settings = new CsvParserSettings()
    settings.setHeaderExtractionEnabled(true)
    settings.detectFormatAutomatically()
    new CsvParser(settings)
  }

  private def processRows: IndexedSeq[Map[String, Any]] =
    processActivityValues.map { case (process, activityValue) =>
      Map(
        "speedMph_timeMin" -> activityValue,
        "county"           -> county,
        "roadCategory"     -> roadCategory,
        "process"          -> process.toString
      ) ++ allPollutants
    }

  private def expectedBin(process: EmissionsProfile.EmissionsProcess, activityValue: Double) = {
    process match {
      case EmissionsProfile.STREX | EmissionsProfile.HOTSOAK | EmissionsProfile.DIURN | EmissionsProfile.RUNLOSS =>
        convertRecordStringToDoubleTypedRange(s"[${activityValue - 0.5},${activityValue + 0.5}]")
      case EmissionsProfile.RUNEX | EmissionsProfile.PMTW | EmissionsProfile.PMBW | EmissionsProfile.PRDUST |
          EmissionsProfile.PTOEX =>
        convertRecordStringToDoubleTypedRange(s"[${activityValue - 2.5},${activityValue + 2.5}]")
      case EmissionsProfile.IDLEX =>
        convertRecordStringToDoubleTypedRange("[0,200]")
    }
  }

  private def assertStore(store: VehicleEmissions.EmissionsRateFilterStore.EmissionsRateFilter): Unit = {
    store.countyToProcessRates should contain(county)
    store.countyToProcessRates.get(county).keySet shouldBe EmissionsProfile.values.map(_.toString).toSet

    processActivityValues.foreach { case (process, activityValue) =>
      val processStore: EmissionsRateFilterStore.ProcessRateIndex =
        store.countyToProcessRates.get(county).get(process.toString)
      processStore.usesRoadCategory shouldBe true
      processStore.specificRoadCategoryToActivityRates.keySet should contain(roadCategory)
      val rc: Array[VehicleEmissions.ActivityRangeEntry[Emissions]] =
        processStore.specificRoadCategoryToActivityRates.get(roadCategory)
      val range: DoubleTypedRange = expectedBin(process, activityValue)
      val emissionsInRange: Option[VehicleEmissions.ActivityRangeEntry[Emissions]] = rc.find(are => are.range == range)
      emissionsInRange.isDefined shouldBe true
      emissionsInRange.get shouldBe expectedEmissions
    }
  }

  private def writeParquet(path: NioPath, rows: IndexedSeq[Map[String, Any]]): Unit = {
    val fields = rows.head.toIndexedSeq.map { case (name, value) =>
      val schema = value match {
        case _: java.lang.Number => Schema.create(Schema.Type.DOUBLE)
        case _                   => Schema.create(Schema.Type.STRING)
      }
      new Schema.Field(name, schema, "", null)
    }
    val schema = Schema.createRecord("EmissionsRateRow", "", "beam.agentsim.agents.vehicles", false, fields.asJava)

    val outputFile = HadoopOutputFile.fromPath(new Path(path.toString), new Configuration())
    val writer = AvroParquetWriter
      .builder[GenericData.Record](outputFile)
      .withSchema(schema)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .build()

    try rows.foreach { row =>
      val record = new GenericData.Record(schema)
      row.foreach { case (name, value) =>
        value match {
          case n: java.lang.Number => record.put(name, n.doubleValue())
          case other               => record.put(name, other.toString)
        }
      }
      writer.write(record)
    } finally writer.close()
  }

  private def writeCsv(path: NioPath, rows: IndexedSeq[Map[String, Any]]): Unit = {
    val header = rows.head.keys.toIndexedSeq
    val body = rows.map { row =>
      header.map(col => row(col).toString).mkString(",")
    }
    Files.write(path, (header.mkString(",") +: body).mkString("\n").getBytes(StandardCharsets.UTF_8))
  }

  private def deleteRecursively(path: NioPath): Unit = {
    if (path == null || !Files.exists(path)) return
    Files.walk(path).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
  }
}
