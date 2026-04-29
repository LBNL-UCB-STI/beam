package beam.router.skim

import beam.router.skim.core.{AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.LazyLogging
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Paths}
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

class ParquetSkimsReadWriteSpec extends AnyWordSpec with BeforeAndAfterAll with LazyLogging with Matchers {
  import ParquetSkimsReadWriteSpec._

  val testFile = "target/parquet-test-output.parquet"

  private def cleanUp(): Unit = {
    Files.deleteIfExists(Paths.get(testFile))
  }

  override def afterAll(): Unit = {
    cleanUp()
    super.afterAll()
  }

  def executeTestWithParams(
    skims: Array[(DummySkimKey, DummySkimVal)],
    parallelism: Int,
    readAfter: Boolean,
    chunkSize: Option[Int] = None
  ): Unit = {
    cleanUp()

    def writeSkims(parallelism: Int, filePath: String): Unit = {
      val writer = new ParquetSkimWriter(
        schema,
        logger,
        createRecord,
        chunkSize = chunkSize.getOrElse(skims.length / parallelism),
        parallelism = parallelism
      )
      val start = System.nanoTime()
      writer.writeSkims(skims, filePath)
      val seconds = (System.nanoTime() - start) / 1e9
      println(s"Write complete [$seconds sec, ${skims.length} size, $parallelism parallelism]")
    }

    def readAndCheckIfEqual(outFilePath: String): Unit = {
      val reader = new ParquetSkimReader(outFilePath, fromParquetRow, logger)
      val skimsOut = reader.readAggregatedSkims

      require(
        skims.length == skimsOut.size,
        s"Size mismatch: generated skims ${skims.length} vs read skims ${skimsOut.size}"
      )
      skims.foreach { case (k, v) =>
        require(
          skimsOut.get(k).contains(v),
          s"Mismatch for key $k: generated skims had $v, read skims had ${skimsOut.get(k)}"
        )
      }
    }

    writeSkims(parallelism, testFile)

    if (readAfter) {
      println(s"Reading back from $testFile ..")
      readAndCheckIfEqual(testFile)
    }

    cleanUp()
  }

  "Parquet Skims write and read" should {
    val size = 100 * 1000
    lazy val skims: Array[(DummySkimKey, DummySkimVal)] = generateRandomEmissionsArray(size)

    "generate skims of correct length" in {
      skims.length mustBe size
    }

    def readAndWriteWithParallelism(parallelism: Int, chunkSize: Option[Int] = None): Unit = {
      val chunksStr = chunkSize match {
        case Some(value) => s", with chunks of $value records"
        case None        => ""
      }
      s"preserve all records when writing and reading back ($size records, parallelism $parallelism$chunksStr)" in {
        executeTestWithParams(skims, parallelism, readAfter = true, chunkSize = chunkSize)
      }
    }

    readAndWriteWithParallelism(1)
    readAndWriteWithParallelism(10)
    readAndWriteWithParallelism(10, Some(size / 21))

    "handle an empty dataset correctly" in {
      val writer = new ParquetSkimWriter(schema, logger, createRecord, 100000, 1)
      writer.writeSkims(Array.empty[(DummySkimKey, DummySkimVal)], testFile)

      val reader = new ParquetSkimReader(testFile, fromParquetRow, logger)
      assert(reader.readAggregatedSkims.isEmpty)
    }
  }
}

object ParquetSkimsReadWriteSpec {

  private def fromParquetRow(row: org.apache.spark.sql.Row): (DummySkimKey, DummySkimVal) = {
    val hour = row.getAs[Int]("hour")
    val linkId = row.getAs[Long]("linkId")
    val vehicle1TypeId = row.getAs[String]("vehicle1TypeId")
    val vehicle2TypeId = row.getAs[String]("vehicle2TypeId")
    val key = DummySkimKey(linkId, vehicle1TypeId, vehicle2TypeId, hour)

    val emissions = row.getAs[String]("emissions")
    val travelTime = row.getAs[Double]("travelTimeInSecond")
    val parkingDuration = row.getAs[Double]("parkingDurationInSecond")
    val observations = row.getAs[Int]("observations")
    val iterations = row.getAs[Int]("iterations")
    val value = DummySkimVal(emissions, travelTime, parkingDuration, observations, iterations)

    (key, value)
  }

  private val linkIdCounter = new AtomicLong(0)
  private def getNextLinkId: Long = linkIdCounter.getAndIncrement()
  private def getRandomHour(rng: ThreadLocalRandom): Int = rng.nextInt(24)

  private def getRandomVehicleTypeId(rng: ThreadLocalRandom): String = {
    val vehicles = Array("Car", "Bus", "Truck", "Motorcycle", "Bike", "RH", "Train", "Ship", "Plane", "Helicopter")
    vehicles(rng.nextInt(vehicles.length))
  }

  private def getRandomEmissions(rng: ThreadLocalRandom): String = {
    val words = Consts.emissionRelatedWords
    val n = words.length
    val picked = (1 until rng.nextInt(7) + 2).map(_ => rng.nextInt(n)).toSet
    val sb = new StringBuilder(64)
    for (idx <- picked) {
      if (sb.nonEmpty) sb.append(';')
      sb.append(words(idx))
      sb.append(':')
      sb.append(rng.nextDouble() * 0.011)
    }
    sb.toString()
  }

  private def getRandomTravelTime(rng: ThreadLocalRandom): Double =
    Math.abs(rng.nextGaussian()) * 10 + 1 // positive, around 1-20
  private def getRandomParkingDuration(rng: ThreadLocalRandom): Double =
    if (rng.nextDouble() < 0.7) 0.0 else rng.nextDouble() * 30
  private def getRandomObservations(rng: ThreadLocalRandom): Int = rng.nextInt(100) + 1
  private def getRandomIterations(rng: ThreadLocalRandom): Int = rng.nextInt(5) + 1

  private def generateRandomEmissionsArray(size: Int): Array[(DummySkimKey, DummySkimVal)] = {
    // .par uses the default ForkJoinPool, which will detect all 72 cores
    (0 until size).par.map { _ =>
      val rnd: ThreadLocalRandom = ThreadLocalRandom.current()

      // Pass 'rnd' into every helper to avoid the synchronized java.util.Random lock
      val key = DummySkimKey(
        linkId = getNextLinkId,
        vehicle1TypeId = getRandomVehicleTypeId(rnd),
        vehicle2TypeId = getRandomVehicleTypeId(rnd),
        hour = getRandomHour(rnd)
      )

      val value = DummySkimVal(
        emissions = getRandomEmissions(rnd),
        travelTime = getRandomTravelTime(rnd),
        parkingDuration = getRandomParkingDuration(rnd),
        observations = getRandomObservations(rnd),
        iterations = getRandomIterations(rnd)
      )
      (key, value)
    }.toArray
  }

  private lazy val schema: Schema = {
    val schemaString =
      """
  {
    "type": "record",
    "name": "EmissionsSkimRecord",
    "namespace": "beam.router.skim.emissions",
    "fields": [
      {"name": "hour", "type": "int"},
      {"name": "linkId", "type": "long"},
      {"name": "vehicle1TypeId", "type": "string"},
      {"name": "vehicle2TypeId", "type": "string"},
      {"name": "emissions", "type": "string"},
      {"name": "travelTimeInSecond", "type": "double"},
      {"name": "parkingDurationInSecond", "type": "double"},
      {"name": "observations", "type": "int"},
      {"name": "iterations", "type": "int"}
    ]
  }
  """
    new Schema.Parser().parse(schemaString)
  }

  private def createRecord(
    schema: Schema,
    key: DummySkimKey,
    value: DummySkimVal
  ): GenericRecord = {
    val record = new GenericData.Record(schema)

    record.put("linkId", key.linkId)
    record.put("hour", key.hour)
    record.put("vehicle1TypeId", key.vehicle1TypeId)
    record.put("vehicle2TypeId", key.vehicle2TypeId)
    record.put("emissions", value.emissions)
    record.put("parkingDurationInSecond", value.parkingDuration)
    record.put("travelTimeInSecond", value.travelTime)
    record.put("observations", value.observations)
    record.put("iterations", value.iterations)

    record
  }
}

case class DummySkimKey(
  linkId: Long,
  vehicle1TypeId: String,
  vehicle2TypeId: String,
  hour: Int
) extends AbstractSkimmerKey {
  override def toCsv: String = ""
}

case class DummySkimVal(
  emissions: String,
  travelTime: Double,
  parkingDuration: Double,
  observations: Int = 0,
  iterations: Int = 0
) extends AbstractSkimmerInternal {
  override def toCsv: String = ""
}

object Consts {

  val emissionRelatedWords: Array[String] = Array(
    "PM",
    "PM10",
    "PM2_5",
    "NOx",
    "CO",
    "HC",
    "SO2",
    "RUNEX",
    "IDLEX",
    "STREX",
    "HOT_SOAK",
    "DIURN",
    "RUN_LOSS",
    "PMTW",
    "PMBW",
    "PR_DUST",
    "CH4",
    "CO",
    "CO2",
    "HC",
    "NH3",
    "NOx",
    "PM",
    "PM10",
    "PM2_5",
    "ROG",
    "SOx",
    "TOG",
    "BC",
    "BCm",
    "BCh"
  )
}
