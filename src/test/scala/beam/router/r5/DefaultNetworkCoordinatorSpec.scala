package beam.router.r5

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._

class DefaultNetworkCoordinatorSpec
    extends WordSpecLike
    with Matchers
    with MockitoSugar
    with TableDrivenPropertyChecks {

  // TODO check test on Windows
  private val beamR5Dir = getClass.getResource("/r5").getPath
  private val beamR5NoFreqsDir = getClass.getResource("/r5-no-freqs").getPath

  private def config(r5Dir: String) =
    ConfigFactory
      .parseString(s"""
                    |beam.routing {
                    |  baseDate = "2016-10-17T00:00:00-07:00"
                    |  transitOnStreetNetwork = true
                    |  r5 {
                    |    directory = $r5Dir
                    |    osmFile = $r5Dir"/test.osm.pbf"
                    |    osmMapdbFile = $r5Dir"/osm.mapdb"
                    |  }
                    |  startingIterationForTravelTimesMSA = 1
                    |}
                    |""".stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  Table(
    ("config", "hasFreqs", "description"),
    (config(beamR5Dir), true, "r5 with frequencies"),
    (config(beamR5NoFreqsDir), false, "r5 without frequencies"),
  ) { (config, hasFrequencies, desc) =>
    s"DefaultNetworkCoordinator on $desc" should {
      val beamConfig = BeamConfig(config)

      "could be created via factory method of NetworkCoordinator" in {
        val networkCoordinator = NetworkCoordinator.create(beamConfig)
        networkCoordinator shouldBe a[DefaultNetworkCoordinator]

      }

      "load GTFS files into a transit layer" in {
        val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
        networkCoordinator.loadNetwork()

        val transitLayer = networkCoordinator.transportNetwork.transitLayer
        transitLayer.hasFrequencies shouldBe hasFrequencies

        val tripPatterns = transitLayer.tripPatterns.asScala
        tripPatterns should have size 4

        val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
        tripSchedules should have size 4

        if (hasFrequencies) {
          tripSchedules.map { ts =>
            (
              ts.tripId,
              ts.startTimes.mkString(","),
              ts.endTimes.mkString(","),
              ts.headwaySeconds.mkString(","),
              ts.arrivals.mkString(","),
              ts.departures.mkString(",")
            )
          } should contain only (expectedTripsSchedules: _*)
        } else {
          tripSchedules.map { ts =>
            (
              ts.tripId,
              ts.startTimes,
              ts.endTimes,
              ts.headwaySeconds,
              ts.arrivals.mkString(","),
              ts.departures.mkString(",")
            )
          } should contain only (expectedTripsNoSchedules: _*)
        }
      }

      "load GTFS files into a transit layer without any adjustments in postLoad" in {
        val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
        networkCoordinator.loadNetwork()
        networkCoordinator.postLoadNetwork()

        val transitLayer = networkCoordinator.transportNetwork.transitLayer
        transitLayer.hasFrequencies shouldBe hasFrequencies

        val tripPatterns = transitLayer.tripPatterns.asScala
        tripPatterns should have size 4

        val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
        tripSchedules should have size 4

        if (hasFrequencies) {
          tripSchedules.map { ts =>
            (
              ts.tripId,
              ts.startTimes.mkString(","),
              ts.endTimes.mkString(","),
              ts.headwaySeconds.mkString(","),
              ts.arrivals.mkString(","),
              ts.departures.mkString(",")
            )
          } should contain only (expectedTripsSchedules: _*)
        } else {
          tripSchedules.map { ts =>
            (
              ts.tripId,
              ts.startTimes,
              ts.endTimes,
              ts.headwaySeconds,
              ts.arrivals.mkString(","),
              ts.departures.mkString(",")
            )
          } should contain only (expectedTripsNoSchedules: _*)
        }
      }

      def expectedTripsSchedules = Seq(
        ("bus:B1-EAST-1", "21600", "79200", "300", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B1-WEST-1", "21600", "79200", "300", "0,210,420,630,840", "120,330,540,750,960"),
        ("train:R2-NORTH-1", "21600", "79200", "600", "0,900", "660,1560"),
        ("train:R2-SOUTH-1", "21600", "79200", "600", "0,900", "660,1560")
      )

      def expectedTripsNoSchedules = Seq(
        ("bus:B1-EAST-1", null, null, null, "21480,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
        ("bus:B1-WEST-1", null, null, null, "21480,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
        ("train:R2-NORTH-1", null, null, null, "20940,21840", "21600,22500"),
        ("train:R2-SOUTH-1", null, null, null, "20940,21840", "21600,22500")
      )

      "convert frequencies to trips after loading network without adjustments" in {
        val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
        networkCoordinator.init() // is for load(), postLoad() and convertFrequenciesToTrips()

        val transitLayer = networkCoordinator.transportNetwork.transitLayer
        transitLayer.hasFrequencies shouldBe false

        val tripPatterns = transitLayer.tripPatterns.asScala
        tripPatterns should have size 4

        val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
        //tripSchedules should have size 1344

        val trips = tripSchedules.map { ts =>
          val s = (
            ts.tripId,
            ts.startTimes,
            ts.endTimes,
            ts.headwaySeconds,
            ts.arrivals.mkString(","),
            ts.departures.mkString(",")
          )
          println(s)
          s
        }
        if (hasFrequencies) {
          trips should contain allOf (
            ("bus:B1-EAST-1-0", null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
            ("bus:B1-EAST-1-1", null, null, null, "21900,22110,22320,22530,22740", "22020,22230,22440,22650,22860"),
            ("bus:B1-EAST-1-2", null, null, null, "22200,22410,22620,22830,23040", "22320,22530,22740,22950,23160"),
            // ...
            ("bus:B1-EAST-1-191", null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
            ("bus:B1-WEST-1-0", null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
            // ...
            ("bus:B1-WEST-1-191", null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
            ("train:R2-NORTH-1-0", null, null, null, "21600,22500", "22260,23160"),
            // ...
            ("train:R2-NORTH-1-95", null, null, null, "78600,79500", "79260,80160"),
            ("train:R2-SOUTH-1-0", null, null, null, "21600,22500", "22260,23160"),
            // ...
            ("train:R2-SOUTH-1-93", null, null, null, "77400,78300", "78060,78960"),
            ("train:R2-SOUTH-1-94", null, null, null, "78000,78900", "78660,79560"),
            ("train:R2-SOUTH-1-95", null, null, null, "78600,79500", "79260,80160")
          )
        } else {
          trips should contain allOf (
            ("bus:B1-EAST-1", null, null, null, "21480,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
            ("bus:B1-WEST-1", null, null, null, "21480,21690,21900,22110,22320", "21600,21810,22020,22230,22440"),
            ("train:R2-NORTH-1", null, null, null, "20940,21840", "21600,22500"),
            ("train:R2-SOUTH-1", null, null, null, "20940,21840", "21600,22500")
          )
        }
      }
    }
  }
}
