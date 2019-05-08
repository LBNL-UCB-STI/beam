package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import scala.util.Random

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
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

  lazy val tAZTreeMap: TAZTreeMap =
    BeamServices.getTazTreeMap("test/test-resources/beam/agentsim/infrastructure/taz-centers.csv")

  def beamServices(config: BeamConfig): BeamServices = {
    val theServices = mock[BeamServices](withSettings().stubOnly())
    val matsimServices = mock[MatsimServices]
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    val geo = new GeoUtilsImpl(config)
    when(theServices.geo).thenReturn(geo)
    theServices
  }

  describe(
    "ZonalParkingManager with no parking"
  ) {
    it("should return a response with the default stall") {

      val config = BeamConfig(system.settings.config)
      val services = beamServices(config)
      val emptyParkingDescription: Iterator[String] = Iterator.empty

      val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, emptyParkingDescription)

      val location = new Coord(170572.95810126758, 2108.0402919341077)
      val inquiry = ParkingInquiry(location, "work", 0.0, None, 0.0)
      val expectedStall: ParkingStall = ParkingStall.DefaultStall(location)

      zonalParkingManager ! inquiry
      expectMsg(ParkingInquiryResponse(expectedStall, inquiry.requestId))
    }
  }

  describe(
    "ZonalParkingManager with one parking option"
  ) {
    it("should first return that only stall, and afterward respond with the default stall") {

      val config = BeamConfig(system.settings.config)
      val services = beamServices(config)
      val oneParkingOption: Iterator[String] =
        """taz,parkingType,pricingModel,chargingPoint,numStalls,feeInCents,reservedFor
          |1,Workplace,FlatFee,TeslaSuperCharger,1,1234,unused
          |
        """.stripMargin.split("\n").toIterator

      val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, oneParkingOption)

      val location = new Coord(170572.95810126758, 2108.0402919341077)
      val inquiry = ParkingInquiry(location, "work", 0.0, None, 0.0)
      val expectedFirstStall =
        ParkingStall(
          Id.create(1, classOf[TAZ]),
          0,
          location,
          1234.0,
          Some(ChargingPointType.TeslaSuperCharger),
          Some(PricingModel.FlatFee(1234, PricingModel.DefaultPricingInterval)),
          ParkingType.Workplace
        )
      val expectedSecondStall = ParkingStall.DefaultStall(location)

      // first request is handled with the only stall in the system
      zonalParkingManager ! inquiry
      expectMsg(ParkingInquiryResponse(expectedFirstStall, inquiry.requestId))

      // second stall is handled with the default stall
      val newInquiry = ParkingInquiry(location, "work", 0.0, None, 0.0)
      zonalParkingManager ! newInquiry
      expectMsg(ParkingInquiryResponse(expectedSecondStall, newInquiry.requestId))

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
    parkingDescription: Iterator[String]
  )(implicit system: ActorSystem): ActorRef = {
    val zonalParkingManagerProps = Props(ZonalParkingManager(parkingDescription, beamServices, new Random(0L)))
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }
}
