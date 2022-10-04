package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingNetworkHelper
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PowerManagerSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach {

  private val config =
    ConfigFactory
      .parseString(s"""
           |beam.agentsim.chargingNetworkManager {
           |  timeStepInSeconds = 300
           |  scaleUp {
           |    enabled = false
           |  }
           |  helics {
           |    connectionEnabled = false
           |    coreInitString = "--federates=1 --broker_address=tcp://127.0.0.1"
           |    coreType = "zmq"
           |    timeDeltaProperty = 1.0
           |    intLogLevel = 1
           |    federateName = "CNMFederate"
           |    dataOutStreamPoint = ""
           |    dataInStreamPoint = ""
           |    bufferSize = 100
           |  }
           |}
      """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  val beamConfig: BeamConfig = BeamConfig(config)
  val beamFederateMock: BeamFederate = mock(classOf[BeamFederate])
  val tazFromBeamville: TAZ = new TAZ(Id.create("1", classOf[TAZ]), new Coord(167141.3, 1112.351), 4840000, None)

  val dummyChargingZone: ParkingZone = ParkingZone.init(
    None,
    tazFromBeamville.tazId,
    ParkingType.Public,
    VehicleManager.AnyManager,
    None,
    maxStalls = 1,
    chargingPointType = Some(ChargingPointType.ChargingStationType1),
    pricingModel = Some(PricingModel.FlatFee(0.0))
  )
  val chargingZones = Map(dummyChargingZone.parkingZoneId -> dummyChargingZone)

  val chargingNetwork: ChargingNetwork = new ChargingNetwork(chargingZones)

  val rideHailNetwork: ChargingNetwork = mock(classOf[ChargingNetwork])

  val dummyChargingStation: ChargingStation = ChargingStation(dummyChargingZone)

  val dummyPhysicalBounds = Map(
    "tazId"                   -> dummyChargingZone.tazId.toString,
    "power_limit_lower"       -> 5678.90,
    "power_limit_upper"       -> 5678.90,
    "lmp_with_control_signal" -> 0.0
  )

  override def beforeEach(): Unit = {
    reset(beamFederateMock)
    when(beamFederateMock.sync(300)).thenReturn(300.0)
    when(beamFederateMock.collectJSON()).thenReturn(Some(List(dummyPhysicalBounds)))
    when(rideHailNetwork.chargingStations).thenReturn(List())
    doReturn(
      List[Map[String, Any]](
        Map(
          "reservedFor"       -> dummyChargingStation.zone.reservedFor,
          "parkingZoneId"     -> dummyChargingStation.zone.parkingZoneId,
          "power_limit_upper" -> 7.2
        )
      ),
      Nil: _*
    ).when(beamFederateMock)
      .cosimulate(
        300,
        Iterable[Map[String, Any]](
          Map(
            "reservedFor"   -> dummyChargingStation.zone.reservedFor,
            "parkingZoneId" -> dummyChargingStation.zone.parkingZoneId,
            "estimatedLoad" -> 5678.90
          )
        )
      )
  }

  "PowerController when connected to grid" should {
    val chargingNetworkHelper: ChargingNetworkHelper = new ChargingNetworkHelper(chargingNetwork, rideHailNetwork) {
      override lazy val allChargingStations: List[ChargingStation] = List(dummyChargingStation)
    }
    val powerController: PowerManager =
      new PowerManager(chargingNetworkHelper, beamConfig) {
        override private[power] lazy val beamFederateOption = Some(beamFederateMock)
      }
    "obtain power physical bounds" in {
      val bounds =
        powerController.obtainPowerPhysicalBounds(300, Map[ChargingStation, Double](dummyChargingStation -> 5678.90))
      bounds shouldBe Map(dummyChargingStation -> 7.2)
    }
  }

  "PowerController when not connected to grid" should {
    val chargingNetworkHelper: ChargingNetworkHelper = ChargingNetworkHelper(chargingNetwork, rideHailNetwork)
    val powerController: PowerManager =
      new PowerManager(chargingNetworkHelper, beamConfig) {
        override private[power] lazy val beamFederateOption = None
      }

    "obtain default (0.0) power physical bounds" in {
      val bounds =
        powerController.obtainPowerPhysicalBounds(300, Map[ChargingStation, Double](dummyChargingStation -> 0.0))
      bounds shouldBe Map()
    }
  }
}
