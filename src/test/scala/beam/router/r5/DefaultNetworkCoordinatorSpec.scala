package beam.router.r5

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._

class DefaultNetworkCoordinatorSpec extends WordSpecLike with Matchers with MockitoSugar {

  private val config = ConfigFactory.load(testConfig("test/input/beamville/beam.conf"))

  "DefaultNetworkCoordinator" should {
    val beamConfig = BeamConfig(config)
    val networkCoordinator = NetworkCoordinator.create(beamConfig)
    networkCoordinator shouldBe a[DefaultNetworkCoordinator]

    networkCoordinator.loadNetwork()

    "load GTFS files into a transit layer" in {
      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe true

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 8

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 8

      tripSchedules.map { ts =>
        (
          ts.tripId,
          ts.startTimes.mkString(","),
          ts.endTimes.mkString(","),
          ts.headwaySeconds.mkString(","),
          ts.frequencyEntryIds.mkString(","),
        )
      } should contain allOf (
        ("bus:B1-EAST-1", "21600", "79200", "300", "bus:B1-EAST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("bus:B1-WEST-1", "21600", "79200", "300", "bus:B1-WEST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("bus:B2-EAST-1", "21600", "79200", "300", "bus:B2-EAST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("bus:B2-WEST-1", "21600", "79200", "300", "bus:B2-WEST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("bus:B3-EAST-1", "21600", "79200", "300", "bus:B3-EAST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("bus:B3-WEST-1", "21600", "79200", "300", "bus:B3-WEST-1_06:00:00_to_22:00:00_every_5m00s"),
        ("train:R2-NORTH-1", "21600", "79200", "600", "train:R2-NORTH-1_06:00:00_to_22:00:00_every_10m00s"),
        ("train:R2-SOUTH-1", "21600", "79200", "600", "train:R2-SOUTH-1_06:00:00_to_22:00:00_every_10m00s")
      )

      tripSchedules.map { ts =>
        (
          ts.tripId,
          ts.arrivals.mkString(","),
          ts.departures.mkString(",")
        )
      } should contain allOf (
        ("bus:B1-EAST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B1-WEST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B2-EAST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B2-WEST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B3-EAST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("bus:B3-WEST-1", "0,210,420,630,840", "120,330,540,750,960"),
        ("train:R2-NORTH-1", "0,900", "660,1560"),
        ("train:R2-SOUTH-1", "0,900", "660,1560")
      )
    }

    "convert frequencies to trips in post load network" in {
      networkCoordinator.postLoadNetwork()

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe false

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 8

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 1344

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
        ("bus:B1-EAST-1-1", null, null, null, null, "21900,22110,22320,22530,22740", "22020,22230,22440,22650,22860"),
        ("bus:B1-EAST-1-2", null, null, null, null, "22200,22410,22620,22830,23040", "22320,22530,22740,22950,23160"),
        // ...
        ("bus:B1-EAST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B1-WEST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B1-WEST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B2-EAST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B2-EAST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B2-WEST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B2-WEST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B3-EAST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B3-EAST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B3-WEST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B3-WEST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
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
  }
}
