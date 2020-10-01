package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.Id
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.List

class SitePowerManagerSpec extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach {

  val beamServicesMock = mock[BeamServices]
  val beamFederateMock = mock[BeamFederate]
  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

  val dummyChargingZone = ChargingZone(
    1,
    Id.create("Dummy", classOf[TAZ]),
    ParkingType.Public,
    1,
    1,
    ChargingPointType.ChargingStationType1,
    PricingModel.FlatFee(0.0)
  )

  private def vehiclesList = List(
    new BeamVehicle(
      Id.createVehicleId("id1"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("PHEV", classOf[BeamVehicleType]))
    ),
    new BeamVehicle(
      Id.createVehicleId("id2"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("CAV", classOf[BeamVehicleType]))
    )
  )

  "SitePowerManager" should {
    val sitePowerManager = new SitePowerManager(Map[Int, ChargingZone](1 -> dummyChargingZone), beamServicesMock)

    "get power over planning horizon 0.0 for charged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe 0.0
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe 10000.0
    }
    "replan horizon and get charging plan per vehicle" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(vehiclesMap.values, 300) shouldBe Map(
        Id.createVehicleId("id1") -> 10000.0
      )
    }
  }

}
