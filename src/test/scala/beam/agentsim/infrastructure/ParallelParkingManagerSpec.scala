package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.InfrastructureUtils.buildParkingZones
import beam.agentsim.infrastructure.parking.PricingModel.FlatFee
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
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
    with AnyFunSpecLike
    with BeforeAndAfterAll
    with ImplicitSender
    with Matchers
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)
  val centerSpaceTime = SpaceTime(coordCenterOfUTM, 0)

  val beamConfig: BeamConfig = BeamConfig(system.settings.config)
  val geo = new GeoUtilsImpl(beamConfig)

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
        parkingManager = ParallelParkingManager.init(
          Map.empty[Id[ParkingZoneId], ParkingZone[TAZ]],
          beamConfig,
          tazTreeMap,
          geo.distUTMInMeters,
          boundingBox,
          randomSeed,
          8
        )
      } {

        val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 11)
        val expectedStall: ParkingStall = ParkingStall.lastResortStall(
          new Envelope(
            inquiry.destinationUtm.loc.getX + 2000,
            inquiry.destinationUtm.loc.getX - 2000,
            inquiry.destinationUtm.loc.getY + 2000,
            inquiry.destinationUtm.loc.getY - 2000
          ),
          new Random(randomSeed),
          tazId = TAZ.EmergencyTAZId,
          geoId = TAZ.EmergencyTAZId
        )

        val response = parkingManager.processParkingInquiry(inquiry)
        assert(response.isDefined, "no response")
        assert(
          response.get == ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId),
          "something is wildly broken"
        )
      }
    }
  }

  describe("ParallelParkingManager with no taz") {
    it("should return a response with an emergency stall") {

      val tazTreeMap = new TAZTreeMap(new QuadTree[TAZ](0, 0, 0, 0))

      val parkingManager = ParallelParkingManager.init(
        Map.empty[Id[ParkingZoneId], ParkingZone[TAZ]],
        beamConfig,
        tazTreeMap,
        geo.distUTMInMeters,
        boundingBox,
        seed = randomSeed,
        8
      )

      val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 173)
      val expectedStall: ParkingStall = ParkingStall.lastResortStall(
        new Envelope(
          inquiry.destinationUtm.loc.getX + 2000,
          inquiry.destinationUtm.loc.getX - 2000,
          inquiry.destinationUtm.loc.getY + 2000,
          inquiry.destinationUtm.loc.getY - 2000
        ),
        random = new Random(randomSeed.toLong),
        tazId = TAZ.EmergencyTAZId,
        geoId = TAZ.EmergencyTAZId
      )

      val response = parkingManager.processParkingInquiry(inquiry)
      assert(response.isDefined, "no response")
      assert(
        response.get == ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId),
        "something is wildly broken"
      )
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
        oneParkingOption: Iterator[String] =
          """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
            |1,Workplace,FlatFee,None,1,1234,
            |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator[TAZ](oneParkingOption, Some(beamConfig), random)
        parkingManager = ParallelParkingManager.init(
          parking.zones.toMap,
          beamConfig,
          tazTreeMap,
          geo.distUTMInMeters,
          boundingBox,
          randomSeed,
          8
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 9902)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            Id.create(1, classOf[TAZ]),
            ParkingZone.createId("cs_Global_1_Workplace_NA_FlatFee_1234_1"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            ParkingZone.GlobalReservedFor
          )
        val response1 = parkingManager.processParkingInquiry(firstInquiry)
        assert(response1.isDefined, "no response")
        assert(
          response1.get == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 237)
        val response2 = parkingManager.processParkingInquiry(secondInquiry)
        response2 match {
          case Some(ParkingInquiryResponse(stall, responseId, triggerId))
              if stall.geoId == TAZ.EmergencyTAZId
                && responseId == secondInquiry.requestId && triggerId == secondInquiry.triggerId =>
          case _ => assert(response2.isDefined, "no response")
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
        oneParkingOption: Iterator[String] =
          """taz,parkingType,pricingModel,chargingPointType,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,None,1,1234,
          |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator[TAZ](oneParkingOption, Some(beamConfig), random)
        parkingManager = ParallelParkingManager.init(
          parking.zones.toMap,
          beamConfig,
          tazTreeMap,
          geo.distUTMInMeters,
          boundingBox,
          randomSeed,
          8
        )

      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3737)
        val secondInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 190)
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            expectedTAZId,
            ParkingZone.createId("cs_Global_1_Workplace_NA_FlatFee_1234_1"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            ParkingZone.GlobalReservedFor
          )

        // request the stall
        val response1 = parkingManager.processParkingInquiry(firstInquiry)
        assert(response1.isDefined, "no response")
        assert(
          response1.get == ParkingInquiryResponse(expectedStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedStall, 777)
        parkingManager.processReleaseParkingStall(releaseParkingStall)

        // request the stall again
        val response2 = parkingManager.processParkingInquiry(secondInquiry)
        assert(response2.isDefined, "no response")
        assert(
          response2.get == ParkingInquiryResponse(expectedStall, secondInquiry.requestId, secondInquiry.triggerId),
          "something is wildly broken"
        )
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
        parking = ParkingZoneFileUtils.fromIterator[TAZ](parkingConfiguration, Some(beamConfig), random)
        parkingManager = ParallelParkingManager.init(
          parking.zones.toMap,
          beamConfig,
          tazTreeMap,
          geo.distUTMInMeters,
          boundingBox,
          randomSeed,
          1 // this test will work only in a single cluster because clusters are fully separated
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry.init(SpaceTime(middleOfWorld, 0), "work", triggerId = 902)
          response1 = parkingManager.processParkingInquiry(req)
          counted = response1 match {
            case Some(res @ ParkingInquiryResponse(_, _, _)) =>
              if (res.stall.geoId != TAZ.EmergencyTAZId) 1 else 0
            case _ =>
              assert(response1.isDefined, "no response")
              0
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
      val stalls = InfrastructureUtils.loadStalls[TAZ](
        "test/input/beamville/parking/taz-parking.csv",
        IndexedSeq.empty,
        tazMap.tazQuadTree,
        1.0,
        1.0,
        randomSeed,
        beamConfig
      )
      val parkingZones = buildParkingZones[TAZ](stalls)
      val zpm = ParallelParkingManager.init(
        parkingZones,
        beamConfig,
        tazMap,
        geo.distUTMInMeters,
        boundingBox,
        randomSeed,
        8
      )

      assertParkingResponse(
        zpm,
        new Coord(170308.0, 2964.0),
        "4",
        ParkingZone.createId("cs_Global_4_Residential_NA_FlatFee_0_2147483647"),
        FlatFee(0.0),
        ParkingType.Residential,
        ParkingZone.GlobalReservedFor
      )

      assertParkingResponse(
        zpm,
        new Coord(166321.0, 1568.0),
        "1",
        ParkingZone.createId("cs_Global_1_Residential_NA_FlatFee_0_2147483647"),
        FlatFee(0.0),
        ParkingType.Residential,
        ParkingZone.GlobalReservedFor
      )

      assertParkingResponse(
        zpm,
        new Coord(167141.3, 3326.017),
        "2",
        ParkingZone.createId("cs_Global_2_Residential_NA_FlatFee_0_2147483647"),
        FlatFee(0.0),
        ParkingType.Residential,
        ParkingZone.GlobalReservedFor
      )
    }
  }

  private def assertParkingResponse(
    spm: ParkingNetwork[_],
    coord: Coord,
    tazId: String,
    parkingZoneId: Id[ParkingZoneId],
    pricingModel: PricingModel,
    parkingType: ParkingType,
    vehicleManagerId: Id[VehicleManager]
  ) = {
    val inquiry = ParkingInquiry.init(SpaceTime(coord, 0), "init", vehicleManagerId, triggerId = 77370)
    val response = spm.processParkingInquiry(inquiry)
    val tazId1 = Id.create(tazId, classOf[TAZ])
    val expectedStall =
      ParkingStall(
        tazId1,
        tazId1,
        parkingZoneId,
        coord,
        0.0,
        None,
        Some(pricingModel),
        parkingType,
        reservedFor = vehicleManagerId
      )
    assert(response.isDefined, "no response")
    assert(
      response.get == ParkingInquiryResponse(expectedStall, inquiry.requestId, inquiry.triggerId),
      "something is wildly broken"
    )
  }

  override def afterAll: Unit = {
    shutdown()
  }
}
