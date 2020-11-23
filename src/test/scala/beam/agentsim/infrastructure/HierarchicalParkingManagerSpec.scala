package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.infrastructure.parking.PricingModel.{Block, FlatFee}
import beam.agentsim.infrastructure.parking.{
  LinkLevelOperations,
  ParkingType,
  ParkingZone,
  ParkingZoneFileUtils,
  PricingModel
}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamHelper
import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

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
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender
    with Matchers
    with BeamHelper
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val randomSeed: Int = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)

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
        parkingManager = system.actorOf(
          HierarchicalParkingManager.props(
            tazTreeMap,
            HierarchicalParkingManagerSpec.mockLinks(tazTreeMap),
            Array.empty[ParkingZone[Link]],
            new Random(randomSeed),
            geo,
            250.0,
            8000.0,
            boundingBox,
            ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
            checkThatNumberOfStallsMatch = true,
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
          tazId = TAZ.EmergencyTAZId,
          geoId = LinkLevelOperations.EmergencyLinkId,
        )

        parkingManager ! inquiry

        expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
      }
    }
  }

  describe("HierarchicalParkingManager with no taz") {
    it("should return a response with an emergency stall") {

      val tazTreeMap = new TAZTreeMap(new QuadTree[TAZ](0, 0, 0, 0))

      val parkingManager = system.actorOf(
        HierarchicalParkingManager.props(
          tazTreeMap,
          HierarchicalParkingManagerSpec.mockLinks(tazTreeMap),
          Array.empty[ParkingZone[Link]],
          new Random(randomSeed),
          geo,
          250.0,
          8000.0,
          boundingBox,
          ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
          checkThatNumberOfStallsMatch = true,
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
        tazId = TAZ.EmergencyTAZId,
        geoId = LinkLevelOperations.EmergencyLinkId,
      )

      parkingManager ! inquiry

      expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
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
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
            |1,Workplace,FlatFee,None,1,1234,unused
            |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator[Link](oneParkingOption, random)
        parkingManager = system.actorOf(
          HierarchicalParkingManager.props(
            tazTreeMap,
            HierarchicalParkingManagerSpec.mockLinks(tazTreeMap),
            parking.zones.toArray,
            new Random(randomSeed),
            geo,
            250.0,
            8000.0,
            boundingBox,
            ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
            checkThatNumberOfStallsMatch = true,
          )
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work")
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
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
              if stall.geoId == LinkLevelOperations.EmergencyLinkId && responseId == secondInquiry.requestId =>
            res
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
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,None,1,1234,unused
          |
          """.stripMargin.split("\n").toIterator
        random = new Random(randomSeed)
        parking = ParkingZoneFileUtils.fromIterator[Link](oneParkingOption, random)
        parkingManager = system.actorOf(
          HierarchicalParkingManager.props(
            tazTreeMap,
            HierarchicalParkingManagerSpec.mockLinks(tazTreeMap),
            parking.zones.toArray,
            new Random(randomSeed),
            geo,
            250.0,
            8000.0,
            boundingBox,
            ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
            checkThatNumberOfStallsMatch = true,
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
        parking = ParkingZoneFileUtils.fromIterator[Link](parkingConfiguration, random)
        parkingManager = system.actorOf(
          HierarchicalParkingManager.props(
            tazTreeMap,
            HierarchicalParkingManagerSpec.mockLinks(tazTreeMap),
            parking.zones.toArray,
            new Random(randomSeed),
            geo,
            250.0,
            8000.0,
            boundingBox,
            ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
            checkThatNumberOfStallsMatch = true,
          )
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry(middleOfWorld, "work")
          _ = parkingManager ! req
          counted = expectMsgPF[Int]() {
            case res: ParkingInquiryResponse =>
              if (res.stall.geoId != LinkLevelOperations.EmergencyLinkId) 1 else 0
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

  describe("HierarchicalParkingManager with loaded common data") {
    it("should return the correct stall") {
      val scenario = loadScenario(beamConfig)
      val (zones, searchTree) = ZonalParkingManager.loadParkingZones[Link](
        "test/input/beamville/parking/link-parking.csv",
        null, //it is required only in case of failures
        1.0,
        1.0,
        new Random(randomSeed),
      )
      val zpm = system.actorOf(
        HierarchicalParkingManager.props(
          scenario.tazTreeMap,
          scenario.linkToTAZMapping,
          zones,
          new Random(randomSeed),
          geo,
          250.0,
          8000.0,
          boundingBox,
          ZonalParkingManager.mnlMultiplierParametersFromConfig(beamConfig),
          // the number of stalls on TAZ and link levels will not match because of big number of stalls
          // which don't fit into Int precision
          checkThatNumberOfStallsMatch = false,
        )
      )

      assertParkingResponse(zpm, new Coord(170308.0, 2964.0), "4", 4033, Block(0.0, 3600), ParkingType.Residential)

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
  ): Any = {
    val inquiry = ParkingInquiry(coord, "init")
    spm ! inquiry
    val rsp = expectMsgClass(classOf[ParkingInquiryResponse])
    rsp.stall.tazId should be(Id.create(tazId, classOf[TAZ]))
    val dist = GeoUtils.distFormula(coord, rsp.stall.locationUTM)
    dist should be <= 400.0
  }

  override def afterAll: Unit = {
    shutdown()
  }
}

object HierarchicalParkingManagerSpec extends MockitoSugar {
  private def mockLinks(tazTreeMap: TAZTreeMap): Map[Link, TAZ] = {
    tazTreeMap.getTAZs
      .flatMap { taz =>
        Array.fill(3)(taz)
      }
      .zipWithIndex
      .map { case (taz, i) => mockLink(taz.coord, i, 100) -> taz }
      .toMap
  }

  def mockLink(coord: Coord, id: Long, len: Double): Link = {
    val link = mock[Link]
    when(link.getCoord).thenReturn(coord)
    when(link.getId).thenReturn(Id.createLinkId(id))
    when(link.getLength).thenReturn(len)
    link
  }
}
