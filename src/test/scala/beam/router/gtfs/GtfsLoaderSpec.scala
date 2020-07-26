package beam.router.gtfs

import java.io.File

import beam.router.gtfs.GtfsLoader.{FilterServiceIdStrategy, TimeFrame}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{Matchers, WordSpecLike}

import scala.concurrent.duration._

class GtfsLoaderSpec extends WordSpecLike with Matchers {
  private val serviceId = "EF33A604"

  "Using test GtfsLoader" when {
    val testDirectory = new File(getClass.getResource("/r5-mod-gtfs").getFile).getAbsolutePath
    val config = ConfigFactory
      .parseString(s"beam.routing.r5.directory=${testDirectory}")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val gtfsLoader = new GtfsLoader(BeamConfig(config))

    val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("train.zip")

    "load trips and stop times from train feed" must {
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
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(tripsAndStopTimes)
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

      val doubledStrategy = gtfsLoader.doubleTripsStrategy(tripsAndStopTimes)
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

      val scaleStrategy = gtfsLoader.scaleTripsStrategy(tripsAndStopTimes, 0.5)
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

  "Using test LI NY GtfsLoader for all serviceIds " when {
    val config = ConfigFactory
      .parseString(s"beam.routing.r5.directory=test/input/ny-gtfs/r5")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val gtfsLoader = new GtfsLoader(BeamConfig(config))
    val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs("Long_Island_Rail_20200215.zip")

    "load trips and stop times from Long_Island_Rail_20200215 feed" must {
      "have 2709 trips sorted by stop times" in {
        tripsAndStopTimes should have size 2709
        tripsAndStopTimes.head.stopTimes should have size 18
        tripsAndStopTimes.head.stopTimes.head.getArrivalTime shouldBe (0.hours + 1.minute).toSeconds

        tripsAndStopTimes.last.stopTimes should have size 12
        tripsAndStopTimes.last.stopTimes.head.getDepartureTime shouldBe (23.hours + 59.minutes).toSeconds
      }
      "have 960 repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        repeatingTrips should have size 960

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012AllServices = repeatingTrips("GO506_20_6012")
        trip6012AllServices should have size 9
        // filtered should be the same because they are on the same service id
        val trip6012 = trip6012AllServices.filter(_._1.trip.getServiceId.getId == serviceId)
        trip6012 should have size 9

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        offset1 shouldBe 0

        val (trip2, offset2) = trip6012(1)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip3, offset3) = trip6012(2)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip4, offset4) = trip6012(3)
        trip4.trip.getId.getId shouldBe "GO506_20_6102"
        offset4 shouldBe (6.hours).toSeconds
      }
      "have 402 repeating trips not taking into account tips' service ids" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes, sameServiceOnly = false)
        repeatingTrips should have size 402

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012AllServices = repeatingTrips("GO506_20_6012")
        trip6012AllServices should have size 22
        // filter by one service to filter out the same trips on different dates
        val trip6012 = trip6012AllServices.filter(_._1.trip.getServiceId.getId == serviceId)
        trip6012 should have size 9

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        offset1 shouldBe 0

        val (trip2, offset2) = trip6012(1)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip3, offset3) = trip6012(2)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip4, offset4) = trip6012(3)
        trip4.trip.getId.getId shouldBe "GO506_20_6102"
        offset4 shouldBe (6.hours).toSeconds
      }
    }
  }

  // For more clear results do testing for trips from one serviceId
  s"Using test LI NY GtfsLoader for particular serviceId $serviceId" when {
    val config = ConfigFactory
      .parseString(s"beam.routing.r5.directory=test/input/ny-gtfs/r5")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val gtfsLoader = new GtfsLoader(BeamConfig(config))
    gtfsLoader.transformGtfs(
      "Long_Island_Rail_20200215.zip",
      s"Long_Island_Rail_20200215-$serviceId.zip",
      List(new FilterServiceIdStrategy(serviceId))
    )

    s"load trips and stop times from Long_Island_Rail_20200215-$serviceId feed" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId.zip")

      "have 518 trips sorted by stop times" in {
        tripsAndStopTimes should have size 518
        tripsAndStopTimes.head.stopTimes should have size 18
        tripsAndStopTimes.head.stopTimes.head.getArrivalTime shouldBe (0.hours + 1.minute).toSeconds

        tripsAndStopTimes.last.stopTimes should have size 12
        tripsAndStopTimes.last.stopTimes.head.getDepartureTime shouldBe (23.hours + 59.minutes).toSeconds
      }
      "have 92 repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimes)
        repeatingTrips should have size 92

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012 = repeatingTrips("GO506_20_6012")
        trip6012 should have size 9

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        trip1.stopTimes.head.getDepartureTime shouldBe (6.hours + 55.minutes).toSeconds
        trip1.stopTimes.last.getDepartureTime shouldBe (8.hours + 13.minutes).toSeconds
        offset1 shouldBe 0

        val (trip2, offset2) = trip6012(1)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip3, offset3) = trip6012(2)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip4, offset4) = trip6012(3)
        trip4.trip.getId.getId shouldBe "GO506_20_6102"
        offset4 shouldBe (6.hours).toSeconds

        val (trip9, offset9) = trip6012(8)
        trip9.trip.getId.getId shouldBe "GO506_20_6144"
        trip9.stopTimes.head.getDepartureTime shouldBe (22.hours + 37.minutes).toSeconds
        trip9.stopTimes.last.getDepartureTime shouldBe (23.hours + 56.minutes).toSeconds
        offset9 shouldBe (15.hours + 42.minutes).toSeconds
      }
    }

    s"load trips and stop times from Long_Island_Rail_20200215-$serviceId feed after doubling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId.zip")

      val factor = 2
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(tripsAndStopTimes, factor)
      gtfsLoader.transformGtfs(
        s"Long_Island_Rail_20200215-$serviceId.zip",
        s"Long_Island_Rail_20200215-$serviceId-doubled-x$factor.zip",
        List(doubledStrategy)
      )
      val tripsAndStopTimesDoubled =
        gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId-doubled-x$factor.zip")

      s"have close to 518x$factor trips sorted by stop times" in {
        tripsAndStopTimesDoubled should have size 1012 // there are a lot of duplicates, that's why it's not exactly 2x518
        tripsAndStopTimesDoubled.head.stopTimes should have size 18
        tripsAndStopTimesDoubled.head.stopTimes.head.getArrivalTime shouldBe (0.hours + 1.minute).toSeconds

        tripsAndStopTimesDoubled.last.stopTimes should have size 12
        tripsAndStopTimesDoubled.last.stopTimes.head.getDepartureTime shouldBe (23.hours + 59.minutes).toSeconds
      }
      s"have 92 repeating trips with x$factor stops" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)
        repeatingTrips should have size 92

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012 = repeatingTrips("GO506_20_6012")
        trip6012 should have size factor * 9

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        offset1 shouldBe 0

        val (trip1c, offset1c) = trip6012(1)
        trip1c.trip.getId.getId shouldBe "GO506_20_6012-clone-1"
        offset1c shouldBe (1.hour).toSeconds

        val (trip2, offset2) = trip6012(2)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip2c, offset2c) = trip6012(3)
        trip2c.trip.getId.getId shouldBe "GO506_20_6020-clone-1"
        offset2c shouldBe (3.hours).toSeconds

        val (trip3, offset3) = trip6012(4)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip3c, offset3c) = trip6012(5)
        trip3c.trip.getId.getId shouldBe "GO506_20_6030-clone-1"
        offset3c shouldBe (5.hours).toSeconds

        val (trip9, offset9) = trip6012(16)
        trip9.trip.getId.getId shouldBe "GO506_20_6144"
        offset9 shouldBe (15.hours + 42.minutes).toSeconds

        val (trip9c, offset9c) = trip6012(17)
        trip9c.trip.getId.getId shouldBe "GO506_20_6144-clone-1"
        offset9c shouldBe (15.hours + 44.minutes).toSeconds
      }
    }
    s"load trips and stop times from Long_Island_Rail_20200215-$serviceId feed after doubling at specified time frame" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId.zip")

      val factor = 2
      val timeFrame = TimeFrame(36000, 50400)
      val doubledStrategy = gtfsLoader.doubleTripsStrategy(tripsAndStopTimes, factor, timeFrame)
      gtfsLoader.transformGtfs(
        s"Long_Island_Rail_20200215-$serviceId.zip",
        s"Long_Island_Rail_20200215-$serviceId-doubled-x$factor-10-14.zip",
        List(doubledStrategy)
      )
      val tripsAndStopTimesDoubled =
        gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId-doubled-x$factor-10-14.zip")

      s"have a bit more than 518 trips sorted by stop times" in {
        tripsAndStopTimesDoubled should have size 598
        tripsAndStopTimesDoubled.head.stopTimes should have size 18
        tripsAndStopTimesDoubled.head.stopTimes.head.getArrivalTime shouldBe (0.hours + 1.minute).toSeconds

        tripsAndStopTimesDoubled.last.stopTimes should have size 12
        tripsAndStopTimesDoubled.last.stopTimes.head.getDepartureTime shouldBe (23.hours + 59.minutes).toSeconds
      }
      s"have 92 repeating trips with x$factor stops for the time frame" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesDoubled)
        repeatingTrips should have size 92

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012 = repeatingTrips("GO506_20_6012")
        trip6012 should have size 9 + 1 // only 1 doubled trips in the time frame

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        offset1 shouldBe 0

        val (trip2, offset2) = trip6012(1)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip3, offset3) = trip6012(2)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip3c, offset3c) = trip6012(3)
        trip3c.trip.getId.getId shouldBe "GO506_20_6030-clone-1"
        offset3c shouldBe (4.hours + 53.minutes + 30.seconds).toSeconds

        val (trip4, offset4) = trip6012(4)
        trip4.trip.getId.getId shouldBe "GO506_20_6102"
        offset4 shouldBe (6.hours).toSeconds

        val (trip9, offset9) = trip6012(9)
        trip9.trip.getId.getId shouldBe "GO506_20_6144"
        offset9 shouldBe (15.hours + 42.minutes).toSeconds
      }
    }
    s"load trips and stop times from Long_Island_Rail_20200215-$serviceId feed after scaling" must {
      val tripsAndStopTimes = gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId.zip")

      val scale = 0.5
      val scaleStrategy = gtfsLoader.scaleTripsStrategy(tripsAndStopTimes, scale)
      gtfsLoader.transformGtfs(
        s"Long_Island_Rail_20200215-$serviceId.zip",
        s"Long_Island_Rail_20200215-$serviceId-scaled-x$scale.zip",
        List(scaleStrategy)
      )
      val tripsAndStopTimesScaled =
        gtfsLoader.loadTripsFromGtfs(s"Long_Island_Rail_20200215-$serviceId-scaled-x$scale.zip")

      s"have 92 scaled by $scale repeating trips" in {
        val repeatingTrips = gtfsLoader.findRepeatingTrips(tripsAndStopTimesScaled)
        repeatingTrips should have size 92

        // a repeating sequence with many elements - the trip itself as a first, and subsequent trips with offsets
        val trip6012 = repeatingTrips("GO506_20_6012")
        trip6012 should have size 9

        val (trip1, offset1) = trip6012(0)
        trip1.trip.getId.getId shouldBe "GO506_20_6012"
        trip1.stopTimes.head.getDepartureTime shouldBe (6.hours + 55.minutes).toSeconds
        trip1.stopTimes.last.getDepartureTime shouldBe (7.hours + 34.minutes).toSeconds
        offset1 shouldBe 0

        val (trip2, offset2) = trip6012(1)
        trip2.trip.getId.getId shouldBe "GO506_20_6020"
        offset2 shouldBe (2.hours).toSeconds

        val (trip3, offset3) = trip6012(2)
        trip3.trip.getId.getId shouldBe "GO506_20_6030"
        offset3 shouldBe (4.hours).toSeconds

        val (trip4, offset4) = trip6012(3)
        trip4.trip.getId.getId shouldBe "GO506_20_6102"
        offset4 shouldBe (6.hours).toSeconds

        val (trip9, offset9) = trip6012(8)
        trip9.trip.getId.getId shouldBe "GO506_20_6144"
        trip9.stopTimes.head.getDepartureTime shouldBe (22.hours + 37.minutes).toSeconds
        trip9.stopTimes.last.getDepartureTime shouldBe (23.hours + 16.minutes + 30.seconds).toSeconds
        offset9 shouldBe (15.hours + 42.minutes).toSeconds
      }
    }
  }
}
