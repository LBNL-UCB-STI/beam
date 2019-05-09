package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

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
    with ImplicitSender {

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
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(List((coordCenterOfUTM, 10000)), startAtId = 1) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        emptyParkingDescription: Iterator[String] = Iterator.empty
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, emptyParkingDescription, new Random(randomSeed))
      } {

        val inquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val expectedStall: ParkingStall = ParkingStall.emergencyStall(new Random(randomSeed))

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
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(List((coordCenterOfUTM, 10000)), startAtId = 1) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        oneParkingOption: Iterator[String] =
          """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
            |1,Workplace,FlatFee,TeslaSuperCharger,1,1234,unused
            |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, oneParkingOption, new Random(randomSeed))
      } {

        // first request is handled with the only stall in the system
        val firstInquiry = ParkingInquiry(coordCenterOfUTM, "work", 0.0, None, 0.0)
        val expectedFirstStall =
          ParkingStall(
            Id.create(1, classOf[TAZ]),
            0,
            coordCenterOfUTM,
            1234.0,
            Some(ChargingPointType.TeslaSuperCharger),
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
            if stall.tazId == TAZ.EmergencyTAZId && responseId == secondInquiry.requestId => res
        }
      }
    }
  }

  describe("ZonalParkingManager with one parking option") {
    it("should allow us to book and then release that stall") {

      for {
        tazTreeMap <- ZonalParkingManagerSpec.mockTazTreeMap(List((coordCenterOfUTM, 10000)), startAtId = 1) // one TAZ at agent coordinate
        config = BeamConfig(system.settings.config)
        services = beamServices(config, tazTreeMap)
        oneParkingOption: Iterator[String] =
        """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,TeslaSuperCharger,1,1234,unused
          |
          """.stripMargin.split("\n").toIterator
        zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, oneParkingOption, new Random(randomSeed))
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
            Some(ChargingPointType.TeslaSuperCharger),
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

  override def afterAll: Unit = {
    shutdown()
  }
}

object ZonalParkingManagerSpec {

  def mockZonalParkingManager(
    beamServices: BeamServices
  )(implicit system: ActorSystem): ActorRef = {
    val zonalParkingManagerProps = Props(ZonalParkingManager(beamServices, new Random(0L)))
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }

  def mockZonalParkingManager(
    beamServices: BeamServices,
    parkingDescription: Iterator[String],
    random: Random = Random
  )(implicit system: ActorSystem): ActorRef = {
    val zonalParkingManagerProps = Props(ZonalParkingManager(parkingDescription, beamServices, random))
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }

  /**
    * creates a mock TAZTreeMap from a list of coordinate/geo area pairs
    * @param coords a list of coordinates paired with the area for the TAZ
    * @param startAtId name each TAZ by integer id ascending, starting from this number
    * @return a mock TAZTreeMap, or nothing
    */
  def mockTazTreeMap(coords: List[(Coord, Double)], startAtId: Int): Option[TAZTreeMap] = {
    if (coords.isEmpty) None
    else {
      val quadTree = coords.
        foldLeft(new QuadTree[TAZ](167000, 0, 833000, 10000000)){ (tree, tazData) =>
          val (coord, area) = tazData
          val tazId = Id.create(startAtId + tree.size, classOf[TAZ])
          val taz = new TAZ(tazId, coord, area)
          tree.put(coord.getX, coord.getY, taz)
          tree
        }
      Some { new TAZTreeMap(quadTree) }
    }
  }
}
