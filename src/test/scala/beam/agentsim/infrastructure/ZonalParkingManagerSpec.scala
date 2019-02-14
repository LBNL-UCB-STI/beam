package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import beam.agentsim.infrastructure.ParkingManager.{
  DepotParkingInquiry,
  DepotParkingInquiryResponse,
  ParkingStockAttributes
}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.MatsimServices
import org.matsim.vehicles.Vehicle
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
    "Depot parking in ZonalParkingManager should return parking stalls according to reservedFor field"
  ) {
    ignore("should return only rideHailManager stalls when all parking are reservedFor RideHailManager") { //none parking stalls if all parking are reserved for RideHailManager and inquiry reserved field is Any

      val config = BeamConfig(
        system.settings.config.withValue(
          "beam.agentsim.taz.parking",
          ConfigValueFactory.fromAnyRef(
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-reserved-rhm.csv"
          )
        )
      )

      val services = beamServices(config)

      val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services)

      val location = new Coord(170572.95810126758, 2108.0402919341077)
      val inquiry = DepotParkingInquiry(Id.create("NA", classOf[Vehicle]), location, ParkingStall.Any)

      zonalParkingManager ! inquiry
      expectMsg(DepotParkingInquiryResponse(None, inquiry.requestId))

      val newInquiry = DepotParkingInquiry(Id.create("NA", classOf[Vehicle]), location, ParkingStall.RideHailManager)
      zonalParkingManager ! newInquiry
      expectMsgPF() {
        case dpier @ DepotParkingInquiryResponse(Some(_), newInquiry.requestId) => dpier
      }
    }

    ignore("should return some stalls when all parking are reservedFor Any") {

      val config = BeamConfig(
        system.settings.config.withValue(
          "beam.agentsim.taz.parking",
          ConfigValueFactory.fromAnyRef(
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-reserved-any.csv"
          )
        )
      )

      val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(beamServices(config))
      val location = new Coord(170572.95810126758, 2108.0402919341077)
      val inquiry = DepotParkingInquiry(Id.create("NA", classOf[Vehicle]), location, ParkingStall.Any)

      zonalParkingManager ! inquiry
      expectMsgPF() {
        case dpier @ DepotParkingInquiryResponse(Some(_), inquiry.requestId) => dpier
      }

      val newInquiry = DepotParkingInquiry(Id.create("NA", classOf[Vehicle]), location, ParkingStall.RideHailManager)
      zonalParkingManager ! newInquiry
      expectMsg(DepotParkingInquiryResponse(None, newInquiry.requestId))
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }
}

object ZonalParkingManagerSpec {

  def mockZonalParkingManager(
    beamServices: BeamServices,
    routerOpt: Option[ActorRef] = None,
    stockAttributesOpt: Option[ParkingStockAttributes] = None
  )(implicit system: ActorSystem): ActorRef = {
    val beamRouterProbe = routerOpt match {
      case Some(router) =>
        router
      case None =>
        TestProbe().ref
    }
    val parkingStockAttributes = stockAttributesOpt match {
      case Some(stockAttrib) =>
        stockAttrib
      case None =>
        ParkingStockAttributes(1)
    }
    val zonalParkingManagerProps = Props(
      new ZonalParkingManager(beamServices, beamRouterProbe, parkingStockAttributes) {
        override def fillInDefaultPooledResources() {} //Ignoring default initialization, just use input data
      }
    )
    TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
  }
}
