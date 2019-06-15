package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import scala.util.Random
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.BeamvilleFixtures
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

class ZonalParkingManagerSpec
    extends TestKit(
      ActorSystem(
        "ZonalParkingManagerSpec",
        ConfigFactory.parseString("""
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  akka.loglevel = debug
  """).withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender
    with Matchers
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

//  lazy val tAZTreeMap: TAZTreeMap = BeamServices.getTazTreeMap("test/test-resources/beam/agentsim/infrastructure/taz-centers.csv")

  def beamServices(config: BeamConfig, tazTreeMap: TAZTreeMap): BeamServices = {
    val theServices = mock[BeamServices](withSettings().stubOnly())
    val matsimServices = mock[MatsimServices]
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.tazTreeMap).thenReturn(tazTreeMap)
    val geo = new GeoUtilsImpl(config)
    when(theServices.geo).thenReturn(geo)
    theServices
  }

  val randomSeed: Long = 0

  // a coordinate in the center of the UTM coordinate system
  val coordCenterOfUTM = new Coord(500000, 5000000)

  describe("ZonalParkingManager with no parking") {
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
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        emptyParkingDescription: Iterator[String] = Iterator.empty
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          services,
          emptyParkingDescription,
          boundingBox,
          new Random(randomSeed)
        )
      } {

        val inquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val expectedStall: ParkingStall = ParkingStall.lastResortStall(boundingBox, new Random(randomSeed))

        zonalParkingManager ! inquiry

        // note on the random seed:
        // since there are no TAZs to search and sample parking locations from,
        // the random number generator is unused by the [[ZonalParkingManager]] search, and we can
        // therefore rely on the coordinate that is generated when [[ZonalParkingManager]] calls [[ParkingStall.emergencyStall]] internally
        expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
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
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
            |1,Workplace,FlatFee,None,1,1234,unused
            |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          services,
          oneParkingOption,
          boundingBox,
          new Random(randomSeed)
        )
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            0,
            coordCenterOfUTM,
            1234.0,
            None,
            Some(PricingModel.FlatFee(1234, PricingModel.DefaultPricingInterval)),
            ParkingType.Workplace
          )
        zonalParkingManager ! firstInquiry
        expectMsg(ParkingInquiryResponse(expectedFirstStall, firstInquiry.requestId))

        // since only stall is in use, the second inquiry will be handled with the emergency stall
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        zonalParkingManager ! secondInquiry
        expectMsgPF() {
          case res @ ParkingInquiryResponse(stall, responseId)
              if stall.tazId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId =>
            res
        }
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
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
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        oneParkingOption: Iterator[String] = """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,None,1,1234,unused
          |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          services,
          oneParkingOption,
          boundingBox,
          new Random(randomSeed)
        )
      } {
        // note: ParkingInquiry constructor has a side effect of creating a new (unique) request id
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val secondInquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val expectedParkingZoneId = 0
        val expectedTAZId = Id.create(1, classOf[TAZ])
        val expectedStall =
          ParkingStall(
            expectedTAZId,
            expectedParkingZoneId,
            coordCenterOfUTM,
            1234.0,
            None,
            Some(PricingModel.FlatFee(1234, PricingModel.DefaultPricingInterval)),
            ParkingType.Workplace
          )

        // request the stall
        zonalParkingManager ! firstInquiry
        expectMsg(ParkingInquiryResponse(expectedStall, firstInquiry.requestId))

        // release the stall
        val releaseParkingStall = ReleaseParkingStall(expectedParkingZoneId)
        zonalParkingManager ! releaseParkingStall

        // request the stall again
        zonalParkingManager ! secondInquiry
        expectMsg(ParkingInquiryResponse(expectedStall, secondInquiry.requestId))
      }
    }
  }

  describe("ZonalParkingManager with a known set of parking alternatives") {
    it("should allow us to book all of those options and then provide us emergency stalls after that point") {

      val random = new Random(1)

      // run this many trials of this test
      val trials = 1
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
        trial <- 1 to trials
        numStalls = math.max(4, random.nextInt(maxParkingStalls))
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(tazList, startAtId = 1, 0, 0, 100, 100)
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        split = ZonalParkingManagerSpec.randomSplitOfMaxStalls(numStalls, 4, random)
        parkingConfiguration: Iterator[String] = ZonalParkingManagerSpec.makeParkingConfiguration(split)
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(
          services,
          parkingConfiguration,
          boundingBox,
          new Random(randomSeed)
        )
      } {

        val wasProvidedNonEmergencyParking: Iterable[Int] = for {
          _ <- 1 to maxInquiries
          req = ParkingInquiry(middleOfWorld, "work", 0.0, None, 0.0)
          _ = zonalParkingManager ! req
          counted = expectMsgPF[Int]() {
            case res @ ParkingInquiryResponse(_, _) =>
              if (res.stall.tazId != TAZ.EmergencyTAZId) 1 else 0
          }
        } yield {
          counted
        }

        // if we counted how many inquiries were handled with non-emergency stalls, we can confirm this should match the numStalls
        // since we intentionally over-saturated parking demand
        val numWithNonEmergencyParking =
          if (wasProvidedNonEmergencyParking.nonEmpty) wasProvidedNonEmergencyParking.sum else 0
        numWithNonEmergencyParking should be(numStalls)
      }
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }
}

object ZonalParkingManagerSpec {

  def mockZonalParkingManager(
    beamServices: BeamServices,
    boundingBox: Envelope
  )(implicit system: ActorSystem): ActorRef = {
    val zonalParkingManagerProps = Props(ZonalParkingManager(beamServices, new Random(0L), boundingBox))
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }

  def mockZonalParkingManager(
    beamServices: BeamServices,
    parkingDescription: Iterator[String],
    boundingBox: Envelope,
    random: Random = Random
  )(implicit system: ActorSystem): ActorRef = {
    val zonalParkingManagerProps = Props(ZonalParkingManager(parkingDescription, beamServices, random, boundingBox))
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }

  /**
    * creates a mock TAZTreeMap from a list of coordinate/geo area pairs
    * @param coords a list of coordinates paired with the area for the TAZ
    * @param startAtId name each TAZ by integer id ascending, starting from this number
    * @return a mock TAZTreeMap, or nothing
    */
  def mockTazTreeMap(
    coords: List[(Coord, Double)],
    startAtId: Int,
    xMin: Double,
    yMin: Double,
    xMax: Double,
    yMax: Double
  ): Option[TAZTreeMap] = {
    if (coords.isEmpty) None
    else {
      val quadTree = coords.foldLeft(new QuadTree[TAZ](xMin, yMin, xMax, yMax)) { (tree, tazData) =>
        val (coord, area) = tazData
        val tazId = Id.create(startAtId + tree.size, classOf[TAZ])
        val taz = new TAZ(tazId, coord, area)
        tree.put(coord.getX, coord.getY, taz)
        tree
      }
      Some { new TAZTreeMap(quadTree) }
    }
  }

  // comes up with a random n-way split of numStalls
  def randomSplitOfMaxStalls(numStalls: Int, numSplits: Int, random: Random): List[Int] = {
    def _sample(remaining: Int, split: List[Int] = List.empty): List[Int] = {
      if (split.length == numSplits - 1) (numStalls - split.sum) +: split
      else {
        val nextSample =
          if (remaining > split.size) random.nextInt(remaining - split.size)
          else if (remaining == numSplits - split.size) 1
          else 0
        val nextRemaining = remaining - nextSample
        _sample(nextRemaining, nextSample +: split)
      }
    }
    if (numStalls <= 0 || numSplits < 1) List.empty
    else {
      val result = _sample(numStalls)
      result
    }
  }

  // using a split of numStalls, create a parking input for all-work, $0-cost parking alternatives with varying stall counts
  def makeParkingConfiguration(split: List[Int]): Iterator[String] = {
    val header = "taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor"
    val result = split.zipWithIndex
      .map {
        case (stalls, i) =>
          s"${i + 1},Workplace,FlatFee,None,$stalls,0,unused"
      }
      .mkString(s"$header\n", "\n", "")
      .split("\n")
      .toIterator
    result
  }
}
