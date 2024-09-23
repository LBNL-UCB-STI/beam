package beam.agentsim.infrastructure

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamHelper
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito.{mock, when}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpecLike
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class HierarchicalParkingManagerSpec
    extends TestKit(
      ActorSystem(
        "HierarchicalParkingManagerSpec",
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
    with BeamHelper
    with BeamvilleFixtures {

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)
  val centerSpaceTime = SpaceTime(coordCenterOfUTM, 0)

  val beamConfig: BeamConfig = BeamConfig(system.settings.config)
  val geo = new GeoUtilsImpl(beamConfig)

  describe("HierarchicalParkingManager with no parking") {
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
        parkingManager = HierarchicalParkingManager.init(
          Map.empty[Id[ParkingZoneId], ParkingZone],
          tazTreeMap,
          geo.distUTMInMeters,
          250.0,
          8000.0,
          boundingBox,
          randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
          checkThatNumberOfStallsMatch = true
        )
      } {

        val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 10)
        val envelope = new Envelope(
          inquiry.destinationUtm.loc.getX + 100,
          inquiry.destinationUtm.loc.getX - 100,
          inquiry.destinationUtm.loc.getY + 100,
          inquiry.destinationUtm.loc.getY - 100
        )
        val response = parkingManager.processParkingInquiry(inquiry)
        assert(response.requestId == 0)
        assert(response.triggerId == 10)
        assert(response.stall.tazId.toString == "emergency")
        assert(envelope.contains(response.stall.locationUTM.getX, response.stall.locationUTM.getY))
      }
    }
  }

  describe("HierarchicalParkingManager with no taz") {
    it("should return a response with an emergency stall") {

      val tazTreeMap = new TAZTreeMap(new QuadTree[TAZ](0, 0, 0, 0))

      val parkingManager = HierarchicalParkingManager.init(
        Map.empty[Id[ParkingZoneId], ParkingZone],
        tazTreeMap,
        geo.distUTMInMeters,
        250.0,
        8000.0,
        boundingBox,
        randomSeed,
        beamConfig.beam.agentsim.agents.parking.multinomialLogit,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
        checkThatNumberOfStallsMatch = true
      )

      val inquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 34347)
      val envelope = new Envelope(
        inquiry.destinationUtm.loc.getX + 100,
        inquiry.destinationUtm.loc.getX - 100,
        inquiry.destinationUtm.loc.getY + 100,
        inquiry.destinationUtm.loc.getY - 100
      )

      val response = parkingManager.processParkingInquiry(inquiry)
      assert(response.triggerId == 34347)
      assert(response.stall.tazId.toString == "emergency")
      assert(envelope.contains(response.stall.locationUTM.getX, response.stall.locationUTM.getY))
    }
  }

  describe("HierarchicalParkingManager with one parking option") {
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
        parking = ParkingZoneFileUtils.fromIterator(
          oneParkingOption,
          Some(beamConfig),
          None,
          random
        )
        parkingManager = HierarchicalParkingManager.init(
          parking.zones.toMap,
          tazTreeMap,
          geo.distUTMInMeters,
          250.0,
          8000.0,
          boundingBox,
          randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
          checkThatNumberOfStallsMatch = true
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", triggerId = 734734)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            reservedFor = VehicleManager.AnyManager
          )
        val response1 = parkingManager.processParkingInquiry(firstInquiry)
        assert(
          response1 == ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry =
          ParkingInquiry.init(centerSpaceTime, "work", triggerId = 3333)
        val response2 = parkingManager.processParkingInquiry(secondInquiry)
        val ParkingInquiryResponse(stall, responseId, secondInquiry.triggerId) = response2
        if (stall.tazId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId) {
          // TODO there should be an assert here
        }
      }
    }
  }

  describe("HierarchicalParkingManager with one parking option") {
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
        parking = ParkingZoneFileUtils
          .fromIterator(
            oneParkingOption,
            Some(beamConfig),
            None,
            random
          )
        parkingManager = HierarchicalParkingManager.init(
          parking.zones.toMap,
          tazTreeMap,
          geo.distUTMInMeters,
          250.0,
          8000.0,
          boundingBox,
          randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
          checkThatNumberOfStallsMatch = true
        )
      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 101)
        val secondInquiry = ParkingInquiry.init(centerSpaceTime, "work", triggerId = 102)
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            ParkingZone.createId("0"),
            coordCenterOfUTM,
            12.34,
            None,
            Some(PricingModel.FlatFee(12.34)),
            ParkingType.Workplace,
            reservedFor = VehicleManager.AnyManager
          )

        // request the stall
        val response1 = parkingManager.processParkingInquiry(firstInquiry)
        assert(
          response1 == ParkingInquiryResponse(expectedStall, firstInquiry.requestId, firstInquiry.triggerId),
          "something is wildly broken"
        )

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedStall, 0)
        parkingManager.processReleaseParkingStall(releaseParkingStall)

        // request the stall again
        val response2 = parkingManager.processParkingInquiry(secondInquiry)
        assert(
          response2 == ParkingInquiryResponse(expectedStall, secondInquiry.requestId, secondInquiry.triggerId),
          "something is wildly broken"
        )
      }
    }
  }

  describe("HierarchicalParkingManager with a known set of parking alternatives") {
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
        parking = ParkingZoneFileUtils.fromIterator(
          parkingConfiguration,
          Some(beamConfig),
          None,
          random
        )
        parkingManager = HierarchicalParkingManager.init(
          parking.zones.toMap,
          tazTreeMap,
          geo.distUTMInMeters,
          250.0,
          8000.0,
          boundingBox,
          randomSeed,
          beamConfig.beam.agentsim.agents.parking.multinomialLogit,
          beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
          checkThatNumberOfStallsMatch = true
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry.init(SpaceTime(middleOfWorld, 0), "work", triggerId = 17)
          response1 = parkingManager.processParkingInquiry(req)
          ParkingInquiryResponse(stall, _, req.triggerId) = response1
          counted = if (stall.tazId != TAZ.EmergencyTAZId) 1 else 0
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

  describe("HierarchicalParkingManager with loaded common data") {
    it("should return the correct stall") {
      val scenario = loadScenario(beamConfig)

      val stalls = InfrastructureUtils
        .loadStalls(
          "test/input/beamville/parking/taz-parking.csv",
          IndexedSeq.empty,
          null, //it is required only in case of failures
          1.0,
          1.0,
          randomSeed,
          beamConfig,
          None
        )
        .filter(_._2.chargingPointType.isEmpty)

      val zpm = HierarchicalParkingManager.init(
        stalls,
        scenario.tazTreeMap,
        geo.distUTMInMeters,
        250.0,
        8000.0,
        boundingBox,
        randomSeed,
        beamConfig.beam.agentsim.agents.parking.multinomialLogit,
        beamConfig.beam.agentsim.agents.parking.estimatedMinParkingDurationInSeconds,
        checkThatNumberOfStallsMatch = true
      )

      assertParkingResponse(
        zpm,
        new Coord(170308.0, 2964.0),
        "4",
        VehicleManager.AnyManager
      )

      assertParkingResponse(
        zpm,
        new Coord(166321.0, 1568.0),
        "1",
        VehicleManager.AnyManager
      )

      assertParkingResponse(
        zpm,
        new Coord(166500.0, 1500.0),
        "1",
        VehicleManager.AnyManager
      )
    }
  }

  private def assertParkingResponse(
    spm: ParkingNetwork,
    coord: Coord,
    tazId: String,
    reservedFor: ReservedFor
  ): Any = {
    val inquiry = ParkingInquiry.init(SpaceTime(coord, 0), "init", reservedFor, triggerId = 27)
    val response = spm.processParkingInquiry(inquiry)
    val ParkingInquiryResponse(stall, _, inquiry.triggerId) = response
    stall.tazId should be(Id.create(tazId, classOf[TAZ]))
    val dist = GeoUtils.distFormula(coord, stall.locationUTM)
    dist should be <= 400.0
  }

  override def afterAll(): Unit = {
    shutdown()
  }
}

object HierarchicalParkingManagerSpec {

  def mockLink(coord: Coord, id: Long, len: Double): Link = {
    val link = mock(classOf[Link])
    when(link.getCoord).thenReturn(coord)
    when(link.getId).thenReturn(Id.createLinkId(id))
    when(link.getLength).thenReturn(len)
    link
  }
}
