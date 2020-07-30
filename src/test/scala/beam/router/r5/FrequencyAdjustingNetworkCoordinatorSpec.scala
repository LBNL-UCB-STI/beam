package beam.router.r5

import java.io.File
import java.nio.file.{Files, Paths}

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import beam.utils.transit.FrequencyAdjustmentUtils
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._

class FrequencyAdjustingNetworkCoordinatorSpec
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with BeforeAndAfterAll {

  private val beamR5Dir: String = new File(getClass.getResource("/r5").getFile).getAbsolutePath.replace('\\', '/')
  private val frequencyAdjustmentFile = s"$beamR5Dir/FrequencyAdjustment.csv"

  override def beforeAll(): Unit = {
    if (!Files.exists(Paths.get(frequencyAdjustmentFile))) {
      val network = DefaultNetworkCoordinator(BeamConfig(config))
      network.loadNetwork()
      FrequencyAdjustmentUtils.generateFrequencyAdjustmentCsvFile(
        network.transportNetwork.transitLayer,
        frequencyAdjustmentFile
      )
    }
  }

  private val config: Config = ConfigFactory
    .parseString(s"""
         |beam.routing {
         |  baseDate = "2016-10-17T00:00:00-07:00"
         |  transitOnStreetNetwork = true
         |  r5 {
         |    directory = "$beamR5Dir"
         |    osmFile = "$beamR5Dir/test.osm.pbf"
         |    osmMapdbFile = "$beamR5Dir/osm.mapdb"
         |  }
         |  startingIterationForTravelTimesMSA = 1
         |}
         |beam.agentsim.scenarios.frequencyAdjustmentFile = $frequencyAdjustmentFile
         |beam.physsim.inputNetworkFilePath = "$beamR5Dir/physsim-network.xml"
         |""".stripMargin)
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  "FrequencyAdjustingNetworkCoordinator" should {
    val beamConfig = BeamConfig(config)

    "be created via factory method of NetworkCoordinator" in {
      val networkCoordinator = NetworkCoordinator.create(beamConfig)
      networkCoordinator shouldBe a[FrequencyAdjustingNetworkCoordinator]
    }

    "load GTFS files into a transit layer" in {
      val networkCoordinator = FrequencyAdjustingNetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe true

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 4

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 4

      tripSchedules.map { ts =>
        (
          ts.tripId,
          ts.startTimes.mkString(","),
          ts.endTimes.mkString(","),
          ts.headwaySeconds.mkString(","),
          ts.arrivals.mkString(","),
          ts.departures.mkString(",")
        )
      } should contain only (expectedNotAdjustedTripsSchedules: _*)
    }

    "load GTFS files into a transit layer and apply adjustment csv without any changes" in {
      val networkCoordinator = FrequencyAdjustingNetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe true

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 4

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 4

      tripSchedules
        .map { ts =>
          (
            ts.tripId,
            ts.startTimes.mkString(","),
            ts.endTimes.mkString(","),
            ts.headwaySeconds.mkString(","),
            ts.arrivals.mkString(","),
            ts.departures.mkString(",")
          )
        } should contain only (expectedNotAdjustedTripsSchedules: _*)
    }

    def expectedNotAdjustedTripsSchedules = Seq(
      ("bus:B1-EAST-1", "21600", "79200", "300", "0,210,420,630,840", "120,330,540,750,960"),
      ("bus:B1-WEST-1", "21600", "79200", "300", "0,210,420,630,840", "120,330,540,750,960"),
      ("train:R2-NORTH-1", "21600", "79200", "600", "0,900", "660,1560"),
      ("train:R2-SOUTH-1", "21600", "79200", "600", "0,900", "660,1560")
    )

    "convert frequencies to trips after loading network without any adjustments" in {
      val networkCoordinator = FrequencyAdjustingNetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()
      networkCoordinator.convertFrequenciesToTrips()

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe false

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 4

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 576

      tripSchedules.map { ts =>
        (
          ts.tripId,
          ts.startTimes,
          ts.endTimes,
          ts.headwaySeconds,
          ts.frequencyEntryIds,
          ts.arrivals.mkString(","),
          ts.departures.mkString(",")
        )
      } should contain allOf (
        ("bus:B1-EAST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        //                                          ^ 300 v      < 210 >
        ("bus:B1-EAST-1-1", null, null, null, null, "21900,22110,22320,22530,22740", "22020,22230,22440,22650,22860"),
        ("bus:B1-EAST-1-2", null, null, null, null, "22200,22410,22620,22830,23040", "22320,22530,22740,22950,23160"),
        // ...
        ("bus:B1-EAST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B1-WEST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B1-WEST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("train:R2-NORTH-1-0", null, null, null, null, "21600,22500", "22260,23160"),
        // ...
        ("train:R2-NORTH-1-95", null, null, null, null, "78600,79500", "79260,80160"),
        ("train:R2-SOUTH-1-0", null, null, null, null, "21600,22500", "22260,23160"),
        // ...
        ("train:R2-SOUTH-1-93", null, null, null, null, "77400,78300", "78060,78960"),
        ("train:R2-SOUTH-1-94", null, null, null, null, "78000,78900", "78660,79560"),
        ("train:R2-SOUTH-1-95", null, null, null, null, "78600,79500", "79260,80160")
      )
    }
    "convert frequencies to trips after loading network after applying adjustments" in {
      val networkCoordinator = FrequencyAdjustingNetworkCoordinator(beamConfig)
      networkCoordinator.init() // load + postload

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe false

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 4

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 576

      tripSchedules.map { ts =>
        (
          ts.tripId,
          ts.startTimes,
          ts.endTimes,
          ts.headwaySeconds,
          ts.frequencyEntryIds,
          ts.arrivals.mkString(","),
          ts.departures.mkString(",")
        )
      } should contain allOf (
        // This returns not very expected results for the first stop time
        ("bus:B1-EAST-1-0", null, null, null, null, "21600,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
        //                                          ^ 300? v     < 210? >
        ("bus:B1-EAST-1-1", null, null, null, null, "21900,21990,22200,22410,22620", "21900,22110,22320,22530,22740"),
        ("bus:B1-EAST-1-2", null, null, null, null, "22200,22290,22500,22710,22920", "22200,22410,22620,22830,23040"),
        // ...
        ("bus:B1-EAST-1-191", null, null, null, null, "78900,78990,79200,79410,79620", "78900,79110,79320,79530,79740"),
        ("bus:B1-WEST-1-0", null, null, null, null, "21600,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
        // ...
        ("bus:B1-WEST-1-191", null, null, null, null, "78900,78990,79200,79410,79620", "78900,79110,79320,79530,79740"),
        ("train:R2-NORTH-1-0", null, null, null, null, "21600,21840", "21600,22500"),
        // ...
        ("train:R2-NORTH-1-95", null, null, null, null, "78600,78840", "78600,79500"),
        ("train:R2-SOUTH-1-0", null, null, null, null, "21600,21840", "21600,22500"),
        // ...
        ("train:R2-SOUTH-1-93", null, null, null, null, "77400,77640", "77400,78300"),
        ("train:R2-SOUTH-1-94", null, null, null, null, "78000,78240", "78000,78900"),
        ("train:R2-SOUTH-1-95", null, null, null, null, "78600,78840", "78600,79500")
      )
    }
  }
}
