package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ChargingNetwork
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.power.SitePowerManager.PhysicalBounds
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PowerControllerSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterEach {

  private val config =
    ConfigFactory
      .parseString(s"""
                      |beam.agentsim.chargingNetworkManager {
                      |  timeStepInSeconds = 300
                      |
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
                      |
                      |  chargingPoint {
                      |    thresholdXFCinKW = 250
                      |    thresholdDCFCinKW = 50
                      |  }
                      |}
                    """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  val beamConfig: BeamConfig = BeamConfig(config)
  val beamFederateMock: BeamFederate = mock(classOf[BeamFederate])
  val tazFromBeamville: TAZ = new TAZ(Id.create("1", classOf[TAZ]), new Coord(167141.3, 1112.351), 4840000)

  val dummyChargingZone: ChargingZone = ChargingZone(
    tazFromBeamville.tazId,
    tazFromBeamville.tazId,
    ParkingType.Public,
    1,
    ChargingPointType.ChargingStationType1,
    PricingModel.FlatFee(0.0),
    None
  )

  val dummyChargingStation: ChargingStation = ChargingStation(dummyChargingZone)

  val dummyPhysicalBounds = Map(
    "tazId"                   -> dummyChargingZone.geoId.toString,
    "power_limit_lower"       -> 5678.90,
    "power_limit_upper"       -> 5678.90,
    "lmp_with_control_signal" -> 0.0
  )

  val zoneTree = new QuadTree[ChargingZone](
    tazFromBeamville.coord.getX,
    tazFromBeamville.coord.getY,
    tazFromBeamville.coord.getX,
    tazFromBeamville.coord.getY
  )

  override def beforeEach: Unit = {
    reset(beamFederateMock)
    when(beamFederateMock.sync(300)).thenReturn(300.0)
    when(beamFederateMock.collectJSON()).thenReturn(List(dummyPhysicalBounds))
    zoneTree.clear()
  }

  "PowerController when connected to grid" should {
    zoneTree.put(tazFromBeamville.coord.getX, tazFromBeamville.coord.getY, dummyChargingZone)
    val powerController: PowerController = new PowerController(
      Map[Option[Id[VehicleManager]], ChargingNetwork](None -> new ChargingNetwork(None, zoneTree)),
      beamConfig
    ) {
      override private[power] lazy val beamFederateOption = Some(beamFederateMock)
    }

    "obtain power physical bounds" in {
      val bounds = powerController.obtainPowerPhysicalBounds(
        300,
        Some(Map[ChargingStation, Double](dummyChargingStation -> 5678.90))
      )
      bounds shouldBe Map(ChargingStation(dummyChargingZone) -> PhysicalBounds(dummyChargingStation, 7.2, 7.2, 0.0))
      // TODO: test beam federate connection
      //verify(beamFederateMock, times(1)).syncAndCollectJSON(300)
    }
  }

  "PowerController when not connected to grid" should {
    zoneTree.put(tazFromBeamville.coord.getX, tazFromBeamville.coord.getY, dummyChargingZone)
    val powerController: PowerController = new PowerController(
      Map[Option[Id[VehicleManager]], ChargingNetwork](None -> new ChargingNetwork(None, zoneTree)),
      beamConfig
    ) {
      override private[power] lazy val beamFederateOption = None
    }

    "obtain default (0.0) power physical bounds" in {
      val bounds =
        powerController.obtainPowerPhysicalBounds(300, Some(Map[ChargingStation, Double](dummyChargingStation -> 0.0)))
      bounds shouldBe Map(ChargingStation(dummyChargingZone) -> PhysicalBounds(dummyChargingStation, 7.2, 7.2, 0.0))
      verify(beamFederateMock, never()).sync(300)
      verify(beamFederateMock, never()).collectJSON()
    }
  }
}
