package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.infrastructure.parking.PricingModel.{Block, FlatFee}
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, ParkingZoneFileUtils, PricingModel}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.util.Random

class ParallelParkingManagerSpec
    extends TestKit(
      ActorSystem(
        "ParallelParkingManagerSpec",
        ConfigFactory
          .parseString("""akka.log-dead-letters = 10
        |akka.actor.debug.fsm = true
        |akka.loglevel = debug
        |akka.test.timefactor = 2""".stripMargin)
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender
    with Matchers
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)

  val beamConfig: BeamConfig = BeamConfig(system.settings.config)
  val geo = new GeoUtilsImpl(beamConfig)

  val emergencyId0: Id[TAZ] = Id.create("emergency-0", classOf[TAZ])

  describe("ParallelParkingManager with no parking") {
    it("should return a response with an emergency stall") {

      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          coords = List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          xMin = 167000,
          yMin = 0,
          xMax = 833000,
          yMax = 10000000
        ) // one TAZ at agent coordinate
        parkingManager = system.actorOf(
          ParallelParkingManager.props(
            beamConfig,
            tazTreeMap,
            Array.empty[ParkingZone],
            Map.empty,
            8,
            geo,
            randomSeed,
            boundingBox,
          )
        )
      } {

        val inquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedStall: ParkingStall = ParkingStall.lastResortStall(
          new Envelope(
            inquiry.destinationUtm.getX + 2000,
            inquiry.destinationUtm.getX - 2000,
            inquiry.destinationUtm.getY + 2000,
            inquiry.destinationUtm.getY - 2000
          ),
          new Random(randomSeed),
          tazId = emergencyId0,
        )

        parkingManager ! inquiry

        expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
      }
    }
  }

  describe("ParallelParkingManager with no taz") {
    it("should return a response with an emergency stall") {

      val tazTreeMap = new TAZTreeMap(new QuadTree[TAZ](0, 0, 0, 0))

      val parkingManager = system.actorOf(
        ParallelParkingManager.props(
          beamConfig,
          tazTreeMap,
          Array.empty[ParkingZone],
          Map.empty,
          8,
          geo,
          randomSeed,
          boundingBox,
        )
      )

      val inquiry = ParkingInquiry(coordCenterOfUTM, "work")
      val expectedStall: ParkingStall = ParkingStall.lastResortStall(
        new Envelope(
          inquiry.destinationUtm.getX + 2000,
          inquiry.destinationUtm.getX - 2000,
          inquiry.destinationUtm.getY + 2000,
          inquiry.destinationUtm.getY - 2000
        ),
        new Random(randomSeed),
        tazId = emergencyId0,
      )

      parkingManager ! inquiry

      expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
    }
  }

  describe("ParallelParkingManager with one parking option") {
    it("should first return that only stall, and afterward respond with the default stall") {

      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          167000,
          0,
          833000,
          10000000
        ) // one TAZ at agent coordinate
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
            |1,Workplace,FlatFee,None,1,1234,unused
            |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator(oneParkingOption, random)
        parkingManager = system.actorOf(
          ParallelParkingManager.props(
            beamConfig,
            tazTreeMap,
            parking.zones,
            parking.tree,
            8,
            geo,
            randomSeed,
            boundingBox,
          )
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            0,
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace
          )
        parkingManager ! firstInquiry
        expectMsg(ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId))

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        parkingManager ! secondInquiry
        expectMsgPF() {
          case res @ ParkingInquiryResponse(stall, responseId)
              if stall.tazId == emergencyId0 && responseId == secondInquiry.requestId =>
            res
        }
      }
    }
  }

  describe("ParallelParkingManager with one parking option") {
    it("should allow us to book and then release that stall") {

      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(
          List((coordCenterOfUTM, 10000)),
          startAtId = 1,
          167000,
          0,
          833000,
          10000000
        ) // one TAZ at agent coordinate
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,None,1,1234,unused
          |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator(oneParkingOption, random)
        parkingManager = system.actorOf(
          ParallelParkingManager.props(
            beamConfig,
            tazTreeMap,
            parking.zones,
            parking.tree,
            8,
            geo,
            randomSeed,
            boundingBox,
          )
        )
      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedParkingZoneId = 0
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            expectedParkingZoneId,
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace
          )

        // request the stall
        parkingManager ! firstInquiry
        expectMsg(ParkingInquiryResponse(expectedStall, firstInquiry.requestId))

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedParkingZoneId, expectedTAZId)
        parkingManager ! releaseParkingStall

        // request the stall again
        parkingManager ! secondInquiry
        expectMsg(ParkingInquiryResponse(expectedStall, secondInquiry.requestId))
      }
    }
  }

  describe("ParallelParkingManager with a known set of parking alternatives") {
    it("should allow us to book all of those options and then provide us emergency stalls after that point") {

      val random1 = new Random(1)

      // run this many trials of this test
      val trials = 5
      // the maximum number of parking stalls across all TAZs in each trial
      val maxParkingStalls = 10000
      // make inquiries (demand) over-saturate parking availability (supply)
      val maxInquiries = (maxParkingStalls.toDouble * 1.25).toInt

      // four square TAZs in a grid
      val tazList: List[(Coord, Double)] = List(
        (new Coord(25, 25), 2500),
        (new Coord(75, 25), 2500),
        (new Coord(25, 75), 2500),
        (new Coord(75, 75), 2500)
      )
      val middleOfWorld = new Coord(50, 50)

      for {
        _ <- 1 to trials
        numStalls = math.max(4, random1.nextInt(maxParkingStalls))
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 100, 100)
        split = ZonalParkingManagerSpec.randomSplitOfMaxStalls(numStalls, 4, random1)
        parkingConfiguration: Iterator[String] = ZonalParkingManagerSpec.makeParkingConfiguration(split)
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator(parkingConfiguration, random)
        parkingManager = system.actorOf(
          ParallelParkingManager.props(
            beamConfig,
            tazTreeMap,
            parking.zones,
            parking.tree,
            1, // this test will work only in a single cluster because clusters are fully separated
            geo,
            randomSeed,
            boundingBox,
          )
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry(middleOfWorld, "work")
          _ = parkingManager ! req
          counted = expectMsgPF[Int]() {
            case res: ParkingInquiryResponse =>
              if (res.stall.tazId != emergencyId0) 1 else 0
          }
        } yield {
          counted
        }

        // if we counted how many inquiries were handled with non-emergency stalls, we can confirm this should match the numStalls
        // since we intentionally over-saturated parking demand
        val numWithNonEmergencyParking = wasProvidedNonEmergencyParking.sum
        numWithNonEmergencyParking should be(numStalls)
      }
    }
  }

  describe("ParallelParkingManager with loaded common data") {
    it("should return the correct stall") {
      val tazMap = taz.TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")
      val (zones, searchTree) = ZonalParkingManager.loadParkingZones(
        "test/input/beamville/parking/taz-parking.csv",
        "/not_set",
        1.0,
        1.0,
        new Random(randomSeed),
      )
      val zpm = system.actorOf(
        ParallelParkingManager.props(
          beamConfig,
          tazMap,
          zones,
          searchTree,
          8,
          geo,
          randomSeed,
          boundingBox,
        )
      )

      assertParkingResponse(zpm, new Coord(170308.0, 2964.0), "4", 105, Block(0.0, 3600), ParkingType.Residential)

      assertParkingResponse(zpm, new Coord(166321.0, 1568.0), "1", 22, FlatFee(0.0), ParkingType.Residential)

      assertParkingResponse(zpm, new Coord(166500.0, 1500.0), "1", 122, Block(0.0, 3600), ParkingType.Public)
    }
  }

  private def assertParkingResponse(
    spm: ActorRef,
    coord: Coord,
    tazId: String,
    parkingZoneId: Int,
    pricingModel: PricingModel,
    parkingType: ParkingType
  ) = {
    val inquiry = ParkingInquiry(coord, "init")
    spm ! inquiry
    val expectedStall =
      ParkingStall(Id.create(tazId, classOf[TAZ]), parkingZoneId, coord, 0.0, None, Some(pricingModel), parkingType)
    expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
  }

  override def afterAll: Unit = {
    shutdown()
  }
}
