package beam.agentsim.infrastructure

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
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
import org.matsim.api.core.v01.Coord
import org.matsim.core.controler.MatsimServices
import org.mockito.Mockito.when
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
  """).withFallback(testConfig("test/input/beamville/beam.conf"))
      )
    )
    with FunSpecLike
    with BeforeAndAfterAll
    with MockitoSugar
    with ImplicitSender {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  val tAZTreeMap: TAZTreeMap =
    BeamServices.getTazTreeMap("test/test-resources/beam/agentsim/infrastructure/taz-centers.csv")

  def beamServices(config: BeamConfig): BeamServices = {
    val theServices = mock[BeamServices]
    val matsimServices = mock[MatsimServices]
    when(theServices.matsimServices).thenReturn(matsimServices)
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.tazTreeMap).thenReturn(tAZTreeMap)
    val geo = new GeoUtilsImpl(theServices)
    when(theServices.geo).thenReturn(geo)
    theServices
  }

  val beamRouterProbe = TestProbe()
  val parkingStockAttributes = ParkingStockAttributes(1)

  describe(
    "Depot parking in ZonalParkingManager should return parking stalls according to reservedFor field"
  ) {
    it("should return only rideHailManager stalls when all parking are reservedFor RideHailManager") { //none parking stalls if all parking are reserved for RideHailManager and inquiry reserved field is Any

      val config = BeamConfig(
        system.settings.config.withValue(
          "beam.agentsim.taz.parking",
          ConfigValueFactory.fromAnyRef(
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-reserved-rhm.csv"
          )
        )
      )

      val zonalParkingManagerProps = Props(
        new ZonalParkingManager(beamServices(config), beamRouterProbe.ref, parkingStockAttributes) {
          override def fillInDefaultPooledResources() {} //Ignoring default initialization, just use input data
        }
      )

      val zonalParkingManager = TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
      val location = new Coord(170572.95810126758, 2108.0402919341077)

      zonalParkingManager ! DepotParkingInquiry(location, ParkingStall.Any)
      expectMsg(DepotParkingInquiryResponse(None))

      zonalParkingManager ! DepotParkingInquiry(location, ParkingStall.RideHailManager)
      expectMsgPF() {
        case dpier @ DepotParkingInquiryResponse(Some(_)) => dpier
      }
    }

    it("should return some stalls when all parking are reservedFor Any") {

      val config = BeamConfig(
        system.settings.config.withValue(
          "beam.agentsim.taz.parking",
          ConfigValueFactory.fromAnyRef(
            "test/test-resources/beam/agentsim/infrastructure/taz-parking-reserved-any.csv"
          )
        )
      )

      val zonalParkingManagerProps = Props(
        new ZonalParkingManager(beamServices(config), beamRouterProbe.ref, parkingStockAttributes) {
          override def fillInDefaultPooledResources() {} //Ignoring default initialization, just use input data
        }
      )

      val zonalParkingManager = TestActorRef[ZonalParkingManager](zonalParkingManagerProps)
      val location = new Coord(170572.95810126758, 2108.0402919341077)

      zonalParkingManager ! DepotParkingInquiry(location, ParkingStall.Any)
      expectMsgPF() {
        case dpier @ DepotParkingInquiryResponse(Some(_)) => dpier
      }

      zonalParkingManager ! DepotParkingInquiry(location, ParkingStall.RideHailManager)
      expectMsg(DepotParkingInquiryResponse(None))
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }
}
