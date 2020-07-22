package beam.router.gtfs

import java.io.File

import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}

class GtfsLoaderSpec extends WordSpecLike with Matchers {

  "Using test GtfsLoader" when {
    val testDirectory = new File(getClass.getResource("/r5-no-freqs").getFile).getAbsolutePath
    val config = ConfigFactory
      .parseString(s"beam.routing.r5.directory=${testDirectory}")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val gtfsLoader = new GtfsLoader(BeamConfig(config))

    "load trips and stop times from train feed" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("train.zip")

      "have 2 trips with 2 stops each" in {
        tripsAndStopTimes.map(_.trip.toString) shouldBe Seq("<Trip rail_R2-SOUTH-1>", "<Trip rail_R2-NORTH-1>")
        for (tst <- tripsAndStopTimes) {
          tst.stopTimes should have size 2
        }
      }
      "have no repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        for ((_, tripWithOffset) <- repeatingTrips) {
          tripWithOffset should have size 1
          tripWithOffset.head._2 shouldBe 0
        }
      }
    }
    "load trips and stop times from train feed after doubling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("train.zip")
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)

      val doubledStrategy = gtfsLoader.doubleTripsStrategy(repeatingTrips)
      gtfsLoader.transformGtfs("train.zip", "train-doubled.zip", List(doubledStrategy))
      val tripsAndStopTimesDoubled = gtfsLoader.loadTripsFromGtfs("train-doubled.zip")

      "have doubled repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)

        for ((_, tripWithOffset) <- repeatingTrips) {
          tripWithOffset should have size 2
        }

        val (trip1, offset1) = repeatingTrips("R2-SOUTH-1")(0)
        trip1.trip.getId.getId shouldBe "R2-SOUTH-1"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(20940, 21840)
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(21600, 22500)
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("R2-SOUTH-1")(1)
        trip2.trip.getId.getId shouldBe "R2-SOUTH-1-clone-1"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(53220, 54120)
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(53880, 54780)
        offset2 shouldBe 32280
      }
    }

    "load trips and stop times from bus feed" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("bus.zip")

      "have 3 trips with 5 stops each" in {
        tripsAndStopTimes.map(_.trip.toString) shouldBe Seq(
          "<Trip bus_B1-EAST-1>",
          "<Trip bus_B1-WEST-1>",
          "<Trip bus_B1-EAST-2>"
        )
        for (tst <- tripsAndStopTimes) {
          tst.stopTimes should have size 5
        }
      }
      "have 1 repeating trip" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        repeatingTrips should have size 2
        repeatingTrips("B1-EAST-1") should have size 2
        repeatingTrips("B1-WEST-1") should have size 1

        val (trip1, offset1) = repeatingTrips("B1-EAST-1")(0)
        trip1.trip.getId.getId shouldBe "B1-EAST-1"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(21480, 21690, 21900, 22110, 22320)
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(21600, 21810, 22020, 22230, 22440)
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("B1-EAST-1")(1)
        trip2.trip.getId.getId shouldBe "B1-EAST-2"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(25080, 25290, 25500, 25710, 25920)
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(25200, 25410, 25620, 25830, 26040)
        offset2 shouldBe 3600
      }
    }
    "load trips and stop times from bus feed after doubling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("bus.zip")
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(repeatingTrips)
      gtfsLoader.transformGtfs("bus.zip", "bus-doubled.zip", List(doubledStrategy))
      val tripsAndStopTimesDoubled = gtfsLoader.loadTripsFromGtfs("bus-doubled.zip")

      "have doubled repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)

        repeatingTrips should have size 2
        repeatingTrips("B1-EAST-1") should have size 4
        repeatingTrips("B1-WEST-1") should have size 2

        val (trip1, offset1) = repeatingTrips("B1-EAST-1")(0)
        trip1.trip.getId.getId shouldBe "B1-EAST-1"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(21480, 21690, 21900, 22110, 22320)
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(21600, 21810, 22020, 22230, 22440)
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("B1-EAST-1")(1)
        trip2.trip.getId.getId shouldBe "B1-EAST-1-clone-1"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(23280, 23490, 23700, 23910, 24120)
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(23400, 23610, 23820, 24030, 24240)
        offset2 shouldBe 1800

        val (trip3, offset3) = repeatingTrips("B1-EAST-1")(2)
        trip3.trip.getId.getId shouldBe "B1-EAST-2"
        trip3.stopTimes.map(_.getArrivalTime) shouldBe Seq(25080, 25290, 25500, 25710, 25920)
        trip3.stopTimes.map(_.getDepartureTime) shouldBe Seq(25200, 25410, 25620, 25830, 26040)
        offset3 shouldBe 3600

        val (trip4, offset4) = repeatingTrips("B1-EAST-1")(3)
        trip4.trip.getId.getId shouldBe "B1-EAST-2-clone-1"
        trip4.stopTimes.map(_.getArrivalTime) shouldBe Seq(55320, 55530, 55740, 55950, 56160)
        trip4.stopTimes.map(_.getDepartureTime) shouldBe Seq(55440, 55650, 55860, 56070, 56280)
        offset4 shouldBe 33840
      }
    }
    "load trips and stop times from bus feed after scaling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("bus.zip")
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes, includeOnlySameService = true)

      val scaleStrategy = gtfsLoader.scaleTripsStrategy(repeatingTrips, 0.5)
      gtfsLoader.transformGtfs("bus.zip", "bus-scaled.zip", List(scaleStrategy))
      val tripsAndStopTimesScaled = gtfsLoader.loadTripsFromGtfs("bus-scaled.zip")

      "have scaled repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesScaled)

        repeatingTrips should have size 2
        repeatingTrips("B1-EAST-1") should have size 2
        repeatingTrips("B1-WEST-1") should have size 1

        val (trip1, offset1) = repeatingTrips("B1-EAST-1")(0)
        trip1.trip.getId.getId shouldBe "B1-EAST-1"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(21480, 21585, 21690, 21795, 21900)
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(21600, 21705, 21810, 21915, 22020)
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("B1-EAST-1")(1)
        trip2.trip.getId.getId shouldBe "B1-EAST-2"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(25080, 25185, 25290, 25395, 25500)
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(25200, 25305, 25410, 25515, 25620)
        offset2 shouldBe 3600

        val (trip3, offset3) = repeatingTrips("B1-WEST-1")(0)
        trip3.trip.getId.getId shouldBe "B1-WEST-1"
        trip3.stopTimes.map(_.getArrivalTime) shouldBe Seq(21480, 21585, 21690, 21795, 21900)
        trip3.stopTimes.map(_.getDepartureTime) shouldBe Seq(21600, 21705, 21810, 21915, 22020)
        offset3 shouldBe 0
      }
    }
  }

  "Using test NY GtfsLoader" when {
    val config = ConfigFactory
      .parseString(s"beam.routing.r5.directory=test/input/ny-gtfs/r5")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val gtfsLoader = new GtfsLoader(BeamConfig(config))
    val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215.zip")

    "load trips and stop times from Long_Island_Rail_20200215 feed" must {
      "have 2709 trips" in {
        tripsAndStopTimes should have size 2709
        tripsAndStopTimes(0).stopTimes should have size 18
        tripsAndStopTimes(1).stopTimes should have size 18
        tripsAndStopTimes(2).stopTimes should have size 18
      }
      "have 402 repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        repeatingTrips should have size 402

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO506_20_2064") should have size 1
        val (trip, offset) = repeatingTrips("GO506_20_2064")(0)
        trip.trip.getId.getId shouldBe "GO506_20_2064"
        offset shouldBe 0

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO506_20_1635") should have size 11
        val (trip1, offset1) = repeatingTrips("GO506_20_1635")(0)
        trip1.trip.getId.getId shouldBe "GO506_20_1635"
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("GO506_20_1635")(1)
        trip2.trip.getId.getId shouldBe "GO506_20_1637"
        offset2 shouldBe 3600

        val (trip3, offset3) = repeatingTrips("GO506_20_1635")(2)
        trip3.trip.getId.getId shouldBe "GO505_20_1637"
        offset3 shouldBe 3600

        val (trip4, offset4) = repeatingTrips("GO506_20_1635")(3)
        trip4.trip.getId.getId shouldBe "GO506_20_1641"
        offset4 shouldBe 7200

        val (trip11, offset11) = repeatingTrips("GO506_20_1635")(10)
        trip11.trip.getId.getId shouldBe "GO506_20_1707"
        offset11 shouldBe 16740
      }
    }
    "load trips and stop times from Long_Island_Rail_20200215 feed after doubling" must {
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)

      val factor = 2
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(repeatingTrips, factor)
      gtfsLoader.transformGtfs(
        "Long_Island_Rail_20200215.zip",
        s"Long_Island_Rail_20200215-doubled-x$factor.zip",
        List(doubledStrategy)
      )
      val tripsAndStopTimesDoubled = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-doubled-x$factor.zip")

      s"have up to 2709x$factor times more trips" in {
        tripsAndStopTimesDoubled should have size 5068 // there are a lot of duplicates, that's why it's not exactly 2x2709
        tripsAndStopTimesDoubled(0).stopTimes should have size 18
        tripsAndStopTimesDoubled(1).stopTimes should have size 18
        tripsAndStopTimesDoubled(2).stopTimes should have size 18
      }
      s"have 402 repeating trips with x$factor stops" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)
        repeatingTrips should have size 402

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO506_20_2064") should have size factor * 1
        val (trip, offset) = repeatingTrips("GO506_20_2064")(0)
        trip.trip.getId.getId shouldBe "GO506_20_2064"
        offset shouldBe 0

        val (tripC, offsetC) = repeatingTrips("GO506_20_2064")(1)
        tripC.trip.getId.getId shouldBe "GO506_20_2064-clone-1"
        offsetC shouldBe 10170

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO505_20_1635") should have size factor * 11
        val (trip1, offset1) = repeatingTrips("GO505_20_1635")(0)
        trip1.trip.getId.getId shouldBe "GO505_20_1635"
        offset1 shouldBe 0

        val (trip1c, offset1c) = repeatingTrips("GO505_20_1635")(1)
        trip1c.trip.getId.getId shouldBe "GO506_20_1635-clone-1"
        offset1c shouldBe 1800

        val (trip11, offset11) = repeatingTrips("GO505_20_1635")(20)
        trip11.trip.getId.getId shouldBe "GO506_20_1707"
        offset11 shouldBe 16740

        val (trip11c, offset11c) = repeatingTrips("GO505_20_1635")(21)
        trip11c.trip.getId.getId shouldBe "GO506_20_1707-clone-1"
        offset11c shouldBe 30750
      }
    }
    "load trips and stop times from Long_Island_Rail_20200215 feed after scaling" must {
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes, includeOnlySameService = true)

      val scale = 0.5
      val scaleStrategy = gtfsLoader.scaleTripsStrategy(repeatingTrips, scale)
      gtfsLoader.transformGtfs(
        "Long_Island_Rail_20200215.zip",
        s"Long_Island_Rail_20200215-scaled-x$scale.zip",
        List(scaleStrategy)
      )
      val tripsAndStopTimesScaled = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-scaled-x$scale.zip")

      s"have 402 scaled by $scale repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesScaled)
        repeatingTrips should have size 402

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO506_20_2064") should have size 1
        val (trip, offset) = repeatingTrips("GO506_20_2064")(0)
        trip.trip.getId.getId shouldBe "GO506_20_2064"
        offset shouldBe 0

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO506_20_1635") should have size 11
        val (trip1, offset1) = repeatingTrips("GO506_20_1635")(0)
        trip1.trip.getId.getId shouldBe "GO506_20_1635"
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("GO506_20_1635")(1)
        trip2.trip.getId.getId shouldBe "GO506_20_1637"
        offset2 shouldBe 3600

        val (trip3, offset3) = repeatingTrips("GO506_20_1635")(2)
        trip3.trip.getId.getId shouldBe "GO505_20_1637"
        offset3 shouldBe 3600

        val (trip4, offset4) = repeatingTrips("GO506_20_1635")(3)
        trip4.trip.getId.getId shouldBe "GO506_20_1641"
        offset4 shouldBe 7200

        val (trip11, offset11) = repeatingTrips("GO506_20_1635")(10)
        trip11.trip.getId.getId shouldBe "GO506_20_1707"
        offset11 shouldBe 16740
      }
    }
  }
}
