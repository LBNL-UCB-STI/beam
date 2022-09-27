package beam.sim

import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.events.PathTraversalEvent
import beam.analysis.plots.passengerpertrip.{CarPassengerPerTrip, GenericPassengerPerTrip, IGraphPassengerPerTrip, TncPassengerPerTrip}
import beam.router.Modes
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.GenericCsvReader
import com.typesafe.config.ConfigFactory
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.{MatsimServices, OutputDirectoryHierarchy}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpecLike
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks.{break, breakable}


class BeamWarmStartRunSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  "Beam WarmStart" must {
    var output = ""
    "run sf-light scenario for two iteration with warmstart" in {

      val baseConf = ConfigFactory
        .parseString(s"""
                       |beam.agentsim.lastIteration = 1
                       |beam.warmStart.type = full
                       |beam.warmStart.path = test/input/sf-light/warmstart
                     """.stripMargin)
        .withFallback(testConfig("test/input/sf-light/sf-light.conf"))
        .resolve()

      output = runBeamWithConfig(baseConf)._2
      val averageCarSpeedIt0 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, "CarRideStats.personal.csv.gz", 0))
      val averageCarSpeedIt1 = BeamWarmStartRunSpec.avgCarModeFromCsv(extractFileName(output, "CarRideStats.personal.csv.gz", 1))
      logger.info("average car speed per iterations: {}, {}", averageCarSpeedIt0, averageCarSpeedIt1)
      averageCarSpeedIt0 / averageCarSpeedIt1 should equal(0.75 +- 0.07)

    }

    "output passenger per trip files with properly omitted columns" in {

      // previous test must be ran
      output should not be ""

      val expectedHeadersWarmStart = Map(
        "passengerPerTripBus.csv" -> Array("hours", "0", "1"),
        "passengerPerTripCableCar.csv" -> Array("hours", "0"),
        "passengerPerTripCar.csv" -> Array("hours", "0"),
        "passengerPerTripRideHail.csv" -> Array("hours", "repositioning", "0", "1"),
        "passengerPerTripSubway.csv" -> Array("hours", "0", "1"),
        "passengerPerTripTram.csv" -> Array("hours", "0", "1"),
      )

      // tests files created by Beam simulation
      testOutputFiles(expectedHeadersWarmStart, output, 0)

      // builds files with edge cases to test
      val expectedHeaders = Map(
        "passengerPerTripCar.csv" -> Array( // CarPassengerPerTrip.java
          Array("hours", "2", "3"),
          Array("hours", "0"),
          Array("hours", "0", "1", "2", "3", "4"),
        ),
        "passengerPerTripRideHail.csv" -> Array( // TncPassengerPerTrip.java
          Array("hours", "repositioning", "2", "3"),
          Array("hours", "repositioning", "0"),
          Array("hours", "repositioning", "1", "2", "3", "4"),
        ),
        "passengerPerTripBus.csv" -> Array( // GenericPassengerPerTrip.java
          Array("hours", "0", "3"),
          Array("hours", "4"),
          Array("hours", "0", "1", "2", "3", "4"),
          Array("hours", "0", "1-8", "25-32"),
          Array("hours", "0", "1-8", "9-16", "17-24", "25-32"),
        ),
      )

      val eventsMap = Map(
        "passengerPerTripCar.csv" -> Array(
          Map(
            // hour -> (numberOfPassengers -> numberOfVehicles)
            6 -> Map(2 -> 1),
            23 -> Map(3 -> 2),
          ),
          Map.empty[Int, Map[Int, Int]],
          Map(
            6 -> Map(0 -> 2),
            7 -> Map(1 -> 5),
            8 -> Map(2 -> 5),
            9 -> Map(3 -> 5),
            10 -> Map(0 -> 2),
            23 -> Map(4 -> 2),
          ),
        ),
        "passengerPerTripRideHail.csv" -> Array(
          Map(
            // hour -> (numberOfPassengers -> numberOfVehicles)
            6 -> Map(2 -> 1),
            23 -> Map(3 -> 2),
          ),
          Map(
            23 -> Map(-1 -> 1),
          ),
          Map(
            6 -> Map(-1 -> 2),
            7 -> Map(1 -> 5),
            8 -> Map(2 -> 5),
            9 -> Map(3 -> 5),
            23 -> Map(4 -> 2),
          ),
        ),
        "passengerPerTripBus.csv" -> Array(
          Map(
            // hour -> (numberOfPassengers -> numberOfVehicles)
            6 -> Map(0 -> 1),
            23 -> Map(3 -> 2),
          ),
          Map(
            23 -> Map(4 -> 2),
          ),
          Map(
            6 -> Map(0 -> 2),
            7 -> Map(1 -> 5),
            8 -> Map(2 -> 5),
            9 -> Map(3 -> 5),
            23 -> Map(4 -> 2),
          ),
          Map (
            6 -> Map(32 -> 1),
            17 -> Map(0 -> 2),
            23 -> Map(1 -> 1),
          ),
          Map(
            6 -> Map(32 -> 1),
            7 -> Map(24 -> 1),
            8 -> Map(16 -> 1),
            9 -> Map(8 -> 2),
            23 -> Map(0 -> 2),
          ),
        ),
      )

      val itr = 9
      val outputDirectoryHierarchy = new OutputDirectoryHierarchy(output, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
      FileUtils.createDirectoryIfNotExists(outputDirectoryHierarchy.getIterationPath(itr))
      val services = mock(classOf[MatsimServices])
      when(services.getControlerIO) thenReturn outputDirectoryHierarchy
      val iterationsEndEvent = new IterationEndsEvent(services, itr)

      for (fn <- eventsMap.keys) {
        for (k <- eventsMap(fn).indices) {
          val events = eventsMap(fn)(k)
          val passengerPerTrip = fn match {
            case "passengerPerTripCar.csv" => new CarPassengerPerTrip("car")
            case "passengerPerTripRideHail.csv" => new TncPassengerPerTrip()
            case _ => new GenericPassengerPerTrip("bus")
          }
          firePathTraversalEvents(passengerPerTrip, events)
          passengerPerTrip.process(iterationsEndEvent)

          // tests file with edge case
          val (header, data) = testOutputFileColumns(fn, expectedHeaders(fn)(k), output, itr)
          // makes sure the csv and events contain the same information
          compareEventsAndCsvData(f"$fn[$k]", events, header, data)
        }
      }
    }
  }


  private def extractFileName(outputDir: String, fileName: String, iterationNumber: Int): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(iterationNumber, fileName)
  }

  private def readCsvOutput(path: String): (Array[String], Array[Array[Double]]) = {
    val reader = new CsvMapReader(FileUtils.getReader(path), CsvPreference.STANDARD_PREFERENCE)
    val header = reader.getHeader(true)
    val data = Iterator
      .continually(reader.read(header: _*))
      .takeWhile(_ != null)
      .map(m => {
        header
          .map(m.get(_))
          .map(_.toDouble)
      }).toArray
    reader.close()
    (header, data)
  }

  private def getZeroFilledColumns(header: Array[String], data: Array[Array[Double]]): List[String] = {
    val zeroFilled = new ListBuffer[String]()
    for (j <- header.indices) {
      var zero = true
      breakable {
        for (i <- data.indices) {
          if (data(i)(j) != 0.0) {
            zero = false
            break
          }
        }
      }
      if (zero) {
        zeroFilled += header(j)
      }
    }
    zeroFilled.toList
  }

  private def createPathTraversalEvent(hour: Int, numberOfPassengers: Int): PathTraversalEvent = {
    // the only fields we care for testing this are time: Double and numberOfPassengers: Int
    new PathTraversalEvent(
      hour * IGraphPassengerPerTrip.SECONDS_IN_HOUR,
      mock(classOf[VehicleId]), // vehicleId is accessed in TncPassengerPerTrip.collectEvent
      "", "", 0, 0, "", "",
      numberOfPassengers,
      0, 0, Modes.BeamMode.WALK, 0.0, null, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, None, None, None, null
    )
  }

  private def firePathTraversalEvents(passengerPerTrip: IGraphPassengerPerTrip, events: Map[Int, Map[Int, Int]]): Unit = {
    for (hour <- events.keys) {
      for (numberOfPassengers <- events(hour).keys) {
        val event = createPathTraversalEvent(hour, numberOfPassengers)
        for (n <- 0 until events(hour)(numberOfPassengers)) {
          passengerPerTrip.collectEvent(event)
        }
      }
    }
  }

  private def testOutputFiles(expectedHeaders: Map[String, Array[String]], output: String, itr: Int): Unit = {

    for (fileName <- expectedHeaders.keys) {
      testOutputFileColumns(fileName, expectedHeaders(fileName), output, itr)
    }
  }

  private def testOutputFileColumns(fileName: String, expectedHeader: Array[String], output: String, itr: Int): (Array[String], Array[Array[Double]]) = {

    val filePath = extractFileName(output, fileName, itr)

    val (header: Array[String], data: Array[Array[Double]]) = readCsvOutput(filePath)

    var zeroFilledColumns = getZeroFilledColumns(header, data)

    // "repositioning" column from passengerPerTripRideHail can be full of zeroes
    if (zeroFilledColumns.contains("repositioning")) {
      zeroFilledColumns = zeroFilledColumns.filter(_ != "repositioning")
    }
    header shouldBe expectedHeader

    // if there is only 1 data column
    // (ignoring hours for all of them and repositioning from passengerPerTripRideHail), it is ok to be all zeroes
    if (header.length > (if (header.contains("repositioning")) 3 else 2)) {
      zeroFilledColumns shouldBe empty
    }

    (header, data)
  }

  private def compareEventsAndCsvData(fileName: String, events: Map[Int, Map[Int, Int]], header: Array[String], data: Array[Array[Double]]): Unit = {
    for (i <- data.indices) {
      val hour = data(i)(0).toInt
      if (events.contains(hour)) {
        for (j <- 1 until data(i).length) {
          var min = -1
          var max = -1
          if (header(j).contains("-")) {
            val min_max = header(j).split("-")
            min = Integer.parseInt(min_max(0))
            max = Integer.parseInt(min_max(1))
          } else if (header(j).equals("repositioning")) {
            min = -1
            max = min
          } else {
            min = Integer.parseInt(header(j))
            max = min
          }

          var sum = 0.0
          for (nPassengers <- min to max) {
            if (events(hour).contains(nPassengers)) {
              sum += events(hour)(nPassengers)
            }
          }

          withClue(f"$fileName column $i, row $j:") {
            data(i)(j) shouldBe sum
          }

        }
      } else {
        for (j <- 1 until data(i).length) {
          withClue(f"$fileName column $i, row $j:") {
            data(i)(j) shouldBe 0.0
          }
        }
      }
    }
  }


}

object BeamWarmStartRunSpec {

  def avgCarModeFromCsv(filePath: String): Double = {
    val (rdr, toClose) =
      GenericCsvReader.readAs[Double](filePath, mapper => mapper.get("travel_time").toDouble, _ => true)
    try {
      val travelTimes = rdr.toArray
      if (travelTimes.length == 0) 0 else travelTimes.sum / travelTimes.length
    } finally {
      toClose.close()
    }
  }
}
