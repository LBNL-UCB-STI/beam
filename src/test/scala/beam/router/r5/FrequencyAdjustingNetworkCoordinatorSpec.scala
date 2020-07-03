package beam.router.r5

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.JavaConverters._

class FrequencyAdjustingNetworkCoordinatorSpec extends WordSpecLike with Matchers with MockitoSugar {

  private val frequencyAdjustmentCsvFile = getClass.getResource("/r5/FrequencyAdjustment.csv").getPath

  private val config = ConfigFactory
    .parseString(s"""beam.agentsim.scenarios.frequencyAdjustmentFile = ${frequencyAdjustmentCsvFile}""")
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  "FrequencyAdjustingNetworkCoordinator" should {
    val beamConfig = BeamConfig(config)
    val networkCoordinator = NetworkCoordinator.create(beamConfig)
    networkCoordinator shouldBe a[FrequencyAdjustingNetworkCoordinator]

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

    "apply adjustment and convert frequencies to trips in post load network" in {
      networkCoordinator.postLoadNetwork()

      val transitLayer = networkCoordinator.transportNetwork.transitLayer
      transitLayer.hasFrequencies shouldBe false

      val tripPatterns = transitLayer.tripPatterns.asScala
      tripPatterns should have size 5

      val tripSchedules = tripPatterns.flatMap(_.tripSchedules.asScala)
      tripSchedules should have size 1044

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
        ("bus:B1-EAST-1-0", null, null, null, null, "21720,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        ("bus:B1-EAST-1-1", null, null, null, null, "21870,21960,22170,22380,22590", "21870,22080,22290,22500,22710"),
        ("bus:B1-EAST-1-2", null, null, null, null, "22020,22110,22320,22530,22740", "22020,22230,22440,22650,22860"),
        // ...
        ("bus:B1-EAST-1-383", null, null, null, null, "79170,79260,79470,79680,79890", "79170,79380,79590,79800,80010"),
        ("bus:B2-EAST-1-0", null, null, null, null, "25200,25290,25500,25710,25920", "25200,25410,25620,25830,26040"),
        // ...
        ("bus:B2-EAST-1-83", null, null, null, null, "75000,75090,75300,75510,75720", "75000,75210,75420,75630,75840"),
        ("bus:B3-EAST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B3-EAST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("bus:B3-WEST-1-0", null, null, null, null, "21600,21810,22020,22230,22440", "21720,21930,22140,22350,22560"),
        // ...
        ("bus:B3-WEST-1-191", null, null, null, null, "78900,79110,79320,79530,79740", "79020,79230,79440,79650,79860"),
        ("train:R2-NORTH-1-0", null, null, null, null, "25200,25440", "25200,26100"),
        // ...
        ("train:R2-NORTH-1-189", null, null, null, null, "81900,82140", "81900,82800"),
        ("train:R2-NORTH-1-190", null, null, null, null, "82200,82440", "82200,83100"),
        ("train:R2-NORTH-1-191", null, null, null, null, "82500,82740", "82500,83400")
      )
    }
  }
}
