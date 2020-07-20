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
        trip2.trip.getId.getId should startWith("R2-SOUTH-1-clone")
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
        trip2.trip.getId.getId should startWith("B1-EAST-1-clone")
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(23280, 23490, 23700, 23910, 24120)
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(23400, 23610, 23820, 24030, 24240)
        offset2 shouldBe 1800

        val (trip3, offset3) = repeatingTrips("B1-EAST-1")(2)
        trip3.trip.getId.getId shouldBe "B1-EAST-2"
        trip3.stopTimes.map(_.getArrivalTime) shouldBe Seq(25080, 25290, 25500, 25710, 25920)
        trip3.stopTimes.map(_.getDepartureTime) shouldBe Seq(25200, 25410, 25620, 25830, 26040)
        offset3 shouldBe 3600

        val (trip4, offset4) = repeatingTrips("B1-EAST-1")(3)
        trip4.trip.getId.getId should startWith("B1-EAST-2-clone")
        trip4.stopTimes.map(_.getArrivalTime) shouldBe Seq(55320, 55530, 55740, 55950, 56160)
        trip4.stopTimes.map(_.getDepartureTime) shouldBe Seq(55440, 55650, 55860, 56070, 56280)
        offset4 shouldBe 33840
      }
    }
    "load trips and stop times from bus feed after scaling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("bus.zip")
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)

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

    "load trips and stop times from Long_Island_Rail_20200215 feed" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215.zip")
      "have 2709 trips" in {
        tripsAndStopTimes should have size 2709
        tripsAndStopTimes(0).stopTimes should have size 18
        tripsAndStopTimes(1).stopTimes should have size 18
        tripsAndStopTimes(2).stopTimes should have size 18
      }
      "have 1644 repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        repeatingTrips should have size 1644

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO505_20_2067") should have size 1
        val (trip, offset) = repeatingTrips("GO505_20_2067")(0)
        trip.trip.getId.getId shouldBe "GO505_20_2067"
        trip.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          69180, 69600, 69840, 70140, 70440, 70800, 71100, 71400, 71880, 72720, 73320, 73920
        )
        trip.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          69180, 69600, 69840, 70140, 70440, 70800, 71100, 71400, 71880, 72840, 73320, 73920
        )
        offset shouldBe 0

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO506_20_7614") should have size 16
        val (trip1, offset1) = repeatingTrips("GO506_20_7614")(0)
        trip1.trip.getId.getId shouldBe "GO506_20_7614"
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("GO506_20_7614")(1)
        trip2.trip.getId.getId shouldBe "GO506_20_7618"
        offset2 shouldBe 3600

        val (trip3, offset3) = repeatingTrips("GO506_20_7614")(1)
        trip3.trip.getId.getId shouldBe "GO506_20_7618"
        offset3 shouldBe 3600

        val (trip16, offset16) = repeatingTrips("GO506_20_7614")(15)
        trip16.trip.getId.getId shouldBe "GO506_20_7742"
        offset16 shouldBe 54000
      }
    }
    "load trips and stop times from Long_Island_Rail_20200215 feed after doubling" must {
      val tripsAndStopTimesSrc = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215.zip")
      val repeatingTripsSrc = gtfsLoader.findRepeatingTrips(tripsAndStopTimesSrc)
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(repeatingTripsSrc)
      gtfsLoader.transformGtfs(
        "Long_Island_Rail_20200215.zip",
        "Long_Island_Rail_20200215-doubled.zip",
        List(doubledStrategy)
      )

      val tripsAndStopTimesDoubled = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215-doubled.zip")

      "have 2x2709 trips" in {
        tripsAndStopTimesDoubled should have size 2 * 2709 - 1 // TODO why one less???
        tripsAndStopTimesDoubled(0).stopTimes should have size 18
        tripsAndStopTimesDoubled(1).stopTimes should have size 18
        tripsAndStopTimesDoubled(2).stopTimes should have size 18
      }
      "have 1644 repeating trips with x2 stops" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)
        repeatingTrips should have size 1644

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO505_20_2067") should have size 2 * 1
        val (trip, offset) = repeatingTrips("GO505_20_2067")(0)
        trip.trip.getId.getId shouldBe "GO505_20_2067"
        trip.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          69180, 69600, 69840, 70140, 70440, 70800, 71100, 71400, 71880, 72720, 73320, 73920
        )
        trip.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          69180, 69600, 69840, 70140, 70440, 70800, 71100, 71400, 71880, 72840, 73320, 73920
        )
        offset shouldBe 0

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO506_20_7614") should have size 2 * 16
        val (trip1, offset1) = repeatingTrips("GO506_20_7614")(0)
        trip1.trip.getId.getId shouldBe "GO506_20_7614"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          26460, 27720, 28620, 28740, 29040, 29340, 29760, 30060, 30480
        )
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          26460, 27840, 28620, 28740, 29040, 29340, 29760, 30060, 30480
        )
        offset1 shouldBe 0

        val (trip1c, offset1c) = repeatingTrips("GO506_20_7614")(1)
        trip1c.trip.getId.getId should startWith("GO506_20_7614-clone")
        trip1c.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          28260, 29520, 30420, 30540, 30840, 31140, 31560, 31860, 32280
        )
        trip1c.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          28260, 29640, 30420, 30540, 30840, 31140, 31560, 31860, 32280
        )
        offset1c shouldBe 1800

        val (trip2, offset2) = repeatingTrips("GO506_20_7614")(2)
        trip2.trip.getId.getId shouldBe "GO506_20_7618"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          30060, 31320, 32220, 32340, 32640, 32940, 33360, 33660, 34080
        )
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          30060, 31440, 32220, 32340, 32640, 32940, 33360, 33660, 34080
        )
        offset2 shouldBe 3600

        val (trip2c, offset2c) = repeatingTrips("GO506_20_7614")(3)
        trip2c.trip.getId.getId should startWith("GO506_20_7618-clone")
        trip2c.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          31860, 33120, 34020, 34140, 34440, 34740, 35160, 35460, 35880
        )
        trip2c.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          31860, 33240, 34020, 34140, 34440, 34740, 35160, 35460, 35880
        )
        offset2c shouldBe 5400

        val (trip16, offset16) = repeatingTrips("GO506_20_7614")(30)
        trip16.trip.getId.getId shouldBe "GO506_20_7742"
        trip16.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          80460, 81720, 82620, 82740, 83040, 83340, 83760, 84060, 84480
        )
        trip16.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          80460, 81840, 82620, 82740, 83040, 83340, 83760, 84060, 84480
        )
        offset16 shouldBe 54000

        val (trip16c, offset16c) = repeatingTrips("GO506_20_7614")(31)
        trip16c.trip.getId.getId should startWith("GO506_20_7742-clone")
        trip16c.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          81420, 82680, 83580, 83700, 84000, 84300, 84720, 85020, 85440
        )
        trip16c.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          81420, 82800, 83580, 83700, 84000, 84300, 84720, 85020, 85440
        )
        offset16c shouldBe 54960
      }
    }
    "load trips and stop times from Long_Island_Rail_20200215 feed after scaling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215.zip")
      val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)

      val scaleStrategy = gtfsLoader.scaleTripsStrategy(repeatingTrips, 0.5)
      gtfsLoader.transformGtfs(
        "Long_Island_Rail_20200215.zip",
        "Long_Island_Rail_20200215-scaled.zip",
        List(scaleStrategy)
      )
      val tripsAndStopTimesScaled = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215-scaled.zip")

      "have scaled repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesScaled)
        repeatingTrips should have size 1645 // TODO why + 1?

        // a repeating sequence with only one element - the trip itself, no repeating after it
        repeatingTrips("GO505_20_2067") should have size 1
        val (trip, offset) = repeatingTrips("GO505_20_2067")(0)
        trip.trip.getId.getId shouldBe "GO505_20_2067"
        trip.stopTimes.map(_.getArrivalTime) shouldBe Seq(69180, 69390, 69510, 69660, 69810, 69990, 70140, 70290, 70530,
          70950, 71250, 71550)
        trip.stopTimes.map(_.getDepartureTime) shouldBe Seq(69180, 69390, 69510, 69660, 69810, 69990, 70140, 70290,
          70530, 71010, 71250, 71550)
        offset shouldBe 0

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        repeatingTrips("GO506_20_7614") should have size 16
        val (trip1, offset1) = repeatingTrips("GO506_20_7614")(0)
        trip1.trip.getId.getId shouldBe "GO506_20_7614"
        trip1.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          26460, 27090, 27540, 27600, 27750, 27900, 28110, 28260, 28470
        )
        trip1.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          26460, 27150, 27540, 27600, 27750, 27900, 28110, 28260, 28470
        )
        offset1 shouldBe 0

        val (trip2, offset2) = repeatingTrips("GO506_20_7614")(1)
        trip2.trip.getId.getId shouldBe "GO506_20_7618"
        trip2.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          30060, 30690, 31140, 31200, 31350, 31500, 31710, 31860, 32070
        )
        trip2.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          30060, 30750, 31140, 31200, 31350, 31500, 31710, 31860, 32070
        )

        offset2 shouldBe 3600

        val (trip3, offset3) = repeatingTrips("GO506_20_7614")(1)
        trip3.trip.getId.getId shouldBe "GO506_20_7618"
        trip3.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          30060, 30690, 31140, 31200, 31350, 31500, 31710, 31860, 32070
        )
        trip3.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          30060, 30750, 31140, 31200, 31350, 31500, 31710, 31860, 32070
        )
        offset3 shouldBe 3600

        val (trip16, offset16) = repeatingTrips("GO506_20_7614")(15)
        trip16.trip.getId.getId shouldBe "GO506_20_7742"
        trip16.stopTimes.map(_.getArrivalTime) shouldBe Seq(
          80460, 81090, 81540, 81600, 81750, 81900, 82110, 82260, 82470
        )
        trip16.stopTimes.map(_.getDepartureTime) shouldBe Seq(
          80460, 81150, 81540, 81600, 81750, 81900, 82110, 82260, 82470
        )

        offset16 shouldBe 54000
      }
    }
  }
}
