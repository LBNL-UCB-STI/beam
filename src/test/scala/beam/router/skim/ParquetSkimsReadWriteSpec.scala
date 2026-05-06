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
      println(s"Write started [${skims.length / 1000}k size, $parallelism par]")
      writer.writeSkims(skims, filePath)
      val path = Paths.get(filePath)
      val fileSizeStr = "%.4f Gb".format(Files.size(path) / (1024.0 * 1024.0 * 1024.0))

      val seconds = (System.nanoTime() - start) / 1e9
      println(
        s"Write complete [$seconds sec, ${skims.length / 1000}k size, $parallelism par, created $fileSizeStr file]"
      )
    }

    def readAndCheckIfEqual(outFilePath: String): Unit = {
      def measure[T](msg: String, f: () => T): T = {
        println(s"Started $msg")
        val start = System.nanoTime()
        val r = f()
        val seconds = (System.nanoTime() - start) / 1e9
        println(s"Complete $msg [$seconds sec]")
        r
      }

      val skimsOut = measure(
        s"read from $outFilePath",
        () => {
          val reader = new ParquetSkimReader(outFilePath, fromParquetRow, logger)
          reader.readAggregatedSkims
        }
      )

      measure(
        "equality check",
        () => {
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
      )
    }

    writeSkims(parallelism, testFile)

    if (readAfter) {
      readAndCheckIfEqual(testFile)
    }

    cleanUp()
  }

  "Parquet Skims write and read" in {
    val size = 100 * 1000
    lazy val skims: Array[(DummySkimKey, DummySkimVal)] = generateRandomEmissionsArray(size)

    s"generate skims array with length ${size / 1000}k" in {
      skims.length mustBe size
    }

    def readAndWriteWithParallelism(parallelism: Int, chunkSize: Option[Int] = None): Unit = {
      val chunksStr = chunkSize match {
        case Some(value) => s", with chunks of $value records"
        case None        => ""
      }
      val info = s"${size / 1000}k records, parallelism $parallelism$chunksStr"
      s"preserve all records when writing and reading back ($info)" in {
        executeTestWithParams(skims, parallelism, readAfter = true, chunkSize = chunkSize)
      }
    }

    readAndWriteWithParallelism(1)
    readAndWriteWithParallelism(10)
    readAndWriteWithParallelism(10, Some(size / 33))

    "handle an empty dataset correctly" in {
      val writer = new ParquetSkimWriter(schema, logger, createRecord, 100000, 1)
      writer.writeSkims(Array.empty[(DummySkimKey, DummySkimVal)], testFile)

      val reader = new ParquetSkimReader(testFile, fromParquetRow, logger)
      assert(reader.readAggregatedSkims.isEmpty)
    }
  }

  "Parquet Skims write and read STRESS TEST" ignore {
    val size = 20 * 1000 * 1000 // 20M records will result in ~1 Gb parquet output file
    lazy val skims: Array[(DummySkimKey, DummySkimVal)] = generateRandomEmissionsArray(size)

    s"generate skims array with length ${size / 1000000}M" in {
      skims.length mustBe size
    }

    def readAndWriteWithParallelism(
      parallelism: Int,
      chunkSize: Int
    ): Unit = {
      val info = s"${size / 1000000}M records, parallelism $parallelism, with chunks of ${chunkSize / 1000}k records"
      s"write and read all records to the output file ($info)" in {
        executeTestWithParams(skims, parallelism, readAfter = true, chunkSize = Some(chunkSize))
      }
    }

    readAndWriteWithParallelism(10, 500 * 1000)
  }

  "Parquet Skims write-only STRESS TEST" ignore {
    val size = 20 * 1000 * 1000 // 20M records will result in ~1 Gb parquet output file
    lazy val skims: Array[(DummySkimKey, DummySkimVal)] = generateRandomEmissionsArray(size)

    s"generate skims array with length ${size / 1000000}M" in {
      skims.length mustBe size
    }

    def writeInParallel(
      parallelism: Int,
      chunkSize: Int
    ): Unit = {
      val info = s"${size / 1000000}M records, parallelism $parallelism, with chunks of ${chunkSize / 1000}k records"
      s"only write all records to the output file ($info)" in {
        executeTestWithParams(skims, parallelism, readAfter = false, chunkSize = Some(chunkSize))
      }
    }

    writeInParallel(10, 500 * 1000)
  }
}

object ParquetSkimsReadWriteSpec {

  private def fromParquetRow(row: Array[Any]): (DummySkimKey, DummySkimVal) = {

    //    {"name": "hour", "type": "int"},
    //    {"name": "linkId", "type": "long"},
    //    {"name": "vehicle1TypeId", "type": "string"},
    //    {"name": "vehicle2TypeId", "type": "string"},
    //    {"name": "emissions", "type": "string"},
    //    {"name": "travelTimeInSecond", "type": "double"},
    //    {"name": "parkingDurationInSecond", "type": "double"},
    //    {"name": "observations", "type": "int"},
    //    {"name": "iterations", "type": "int"}

    val hour = row(0).asInstanceOf[Int]
    val linkId = row(1).asInstanceOf[Long]
    val vehicle1TypeId = row(2).asInstanceOf[String]
    val vehicle2TypeId = row(3).asInstanceOf[String]
    val key = DummySkimKey(linkId, vehicle1TypeId, vehicle2TypeId, hour)

    val emissions = row(4).asInstanceOf[String]
    val travelTime = row(5).asInstanceOf[Double]
    val parkingDuration = row(6).asInstanceOf[Double]
    val observations = row(7).asInstanceOf[Int]
    val iterations = row(8).asInstanceOf[Int]
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
    val arr = new Array[(DummySkimKey, DummySkimVal)](size)

    (0 until size).par.foreach { i =>
      val rnd = ThreadLocalRandom.current()
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
      arr(i) = (key, value)
    }

    arr
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
