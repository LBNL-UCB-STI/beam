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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.concurrent.TrieMap

class PowerControllerSpec extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach {

  private val config =
    ConfigFactory
      .parseString(s"""
                      |beam.agentsim.chargingNetworkManager {
                      |  timeStepInSeconds = 300
                      |
                      |  helics {
                      |    connectionEnabled = false
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
  val beamFederateMock: BeamFederate = mock[BeamFederate]
  val tazFromBeamville: TAZ = new TAZ(Id.create("1", classOf[TAZ]), new Coord(167141.3, 1112.351), 4840000)

  val dummyChargingZone: ChargingZone = ChargingZone(
    1,
    tazFromBeamville.tazId,
    ParkingType.Public,
    1,
    ChargingPointType.ChargingStationType1,
    PricingModel.FlatFee(0.0),
    VehicleManager.privateVehicleManager.managerId
  )

  val dummyChargingStation: ChargingStation = ChargingStation(dummyChargingZone)

  val dummyPhysicalBounds = Map(
    "tazId"   -> dummyChargingZone.tazId.toString,
    "zoneId"  -> dummyChargingZone.chargingZoneId,
    "minLoad" -> 5678.90,
    "maxLoad" -> 5678.90
  )

  val zoneTree = new QuadTree[ChargingZone](
    tazFromBeamville.coord.getX,
    tazFromBeamville.coord.getY,
    tazFromBeamville.coord.getX,
    tazFromBeamville.coord.getY
  )

  override def beforeEach: Unit = {
    reset(beamFederateMock)
    when(beamFederateMock.syncAndCollectJSON(300)).thenReturn((300.0, List(dummyPhysicalBounds)))
    zoneTree.clear()
  }

  "PowerController when connected to grid" should {
    zoneTree.put(tazFromBeamville.coord.getX, tazFromBeamville.coord.getY, dummyChargingZone)
    val powerController: PowerController = new PowerController(
      Map[Id[VehicleManager], ChargingNetwork](
        VehicleManager.privateVehicleManager.managerId -> new ChargingNetwork(
          VehicleManager.privateVehicleManager.managerId,
          zoneTree
        )
      ),
      beamConfig
    ) {
      override private[power] lazy val beamFederateOption = Some(beamFederateMock)
    }

    "obtain power physical bounds" in {
      val bounds = powerController.obtainPowerPhysicalBounds(
        300,
        Some(Map[ChargingStation, Double](dummyChargingStation -> 5678.90))
      )
      bounds shouldBe Map(ChargingStation(dummyChargingZone) -> PhysicalBounds(dummyChargingStation, 7.2))
      // TODO: test beam federate connection
      //verify(beamFederateMock, times(1)).syncAndCollectJSON(300)
    }
  }

  "PowerController when not connected to grid" should {
    zoneTree.put(tazFromBeamville.coord.getX, tazFromBeamville.coord.getY, dummyChargingZone)
    val powerController: PowerController = new PowerController(
      Map[Id[VehicleManager], ChargingNetwork](
        VehicleManager.privateVehicleManager.managerId -> new ChargingNetwork(
          VehicleManager.privateVehicleManager.managerId,
          zoneTree
        )
      ),
      beamConfig
    ) {
      override private[power] lazy val beamFederateOption = None
    }

    "obtain default (0.0) power physical bounds" in {
      val bounds =
        powerController.obtainPowerPhysicalBounds(300, Some(Map[ChargingStation, Double](dummyChargingStation -> 0.0)))
      bounds shouldBe Map(ChargingStation(dummyChargingZone) -> PhysicalBounds(dummyChargingStation, 7.2))
      verify(beamFederateMock, never()).syncAndCollectJSON(300)
    }

  }
}
