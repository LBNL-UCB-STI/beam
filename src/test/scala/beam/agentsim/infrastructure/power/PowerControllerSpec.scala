package beam.agentsim.infrastructure.power

import beam.agentsim.infrastructure.ChargingNetworkManager.{ChargingZone, PhysicalBounds}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class PowerControllerSpec extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach {

  private val config =
    ConfigFactory
      .parseString(s"""
                      |beam.agentsim.chargingNetworkManager {
                      |  gridConnectionEnabled = false
                      |  chargingSessionInSeconds = 300
                      |  planningHorizonInSec = 300
                      |  helicsFederateName = "BeamCNM"
                      |}
                    """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  val beamFederateMock: BeamFederate = mock[BeamFederate]

  val dummyChargingZone: ChargingZone = ChargingZone(
    1,
    Id.create("Dummy", classOf[TAZ]),
    ParkingType.Public,
    1,
    1,
    ChargingPointType.ChargingStationType1,
    PricingModel.FlatFee(0.0)
  )

  val dummyPhysicalBounds = Map(
    "tazId"   -> dummyChargingZone.tazId.toString,
    "zoneId"  -> dummyChargingZone.chargingZoneId,
    "minLoad" -> 5678.90,
    "maxLoad" -> 5678.90
  )

  override def beforeEach: Unit = {
    reset(beamFederateMock)
    when(beamFederateMock.syncAndCollectJSON(300)).thenReturn((300.0, List(dummyPhysicalBounds)))
  }

  "PowerController when connected to grid" should {
    val powerController: PowerController = new PowerController(BeamConfig(config)) {
      override private[power] lazy val beamFederateOption = Some(beamFederateMock)
    }

    "obtain power physical bounds" in {
      val bounds =
        powerController.obtainPowerPhysicalBounds(
          300,
          Map[Int, ChargingZone](dummyChargingZone.chargingZoneId -> dummyChargingZone),
          Some(Map[Int, Double](dummyChargingZone.chargingZoneId -> 5678.90))
        )
      bounds shouldBe Map(
        dummyChargingZone.chargingZoneId -> PhysicalBounds(
          dummyChargingZone.tazId,
          dummyChargingZone.chargingZoneId,
          7.2,
          7.2
        )
      )
      //verify(beamFederateMock, times(1)).syncAndCollectJSON(300)
    }
  }
  "PowerController when not connected to grid" should {
    val powerController: PowerController = new PowerController(BeamConfig(config)) {
      override private[power] lazy val beamFederateOption = None
    }

    "obtain default (0.0) power physical bounds" in {
      val bounds = powerController.obtainPowerPhysicalBounds(
        300,
        Map[Int, ChargingZone](dummyChargingZone.chargingZoneId -> dummyChargingZone),
        Some(Map[Int, Double](dummyChargingZone.chargingZoneId -> 0.0))
      )
      bounds shouldBe Map(
        dummyChargingZone.chargingZoneId -> PhysicalBounds(
          dummyChargingZone.tazId,
          dummyChargingZone.chargingZoneId,
          7.2,
          7.2
        )
      )
      //verify(beamFederateMock, never()).syncAndCollectJSON(300)
    }

  }
}
