package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.power.PowerController.PhysicalBounds
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.Id
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.immutable.List

class SitePowerManagerSpec extends WordSpecLike with Matchers {

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")

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
    val sitePowerManager = new SitePowerManager

    "get power over planning horizon 0.0 for charged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      sitePowerManager.getPowerOverPlanningHorizon(vehiclesMap) shouldBe 0.0
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.getPowerOverPlanningHorizon(vehiclesMap) shouldBe 10000.0
    }
    "replan horizon and get charging plan per vehicle" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(PhysicalBounds.default, vehiclesMap) shouldBe Map(
        Id.createVehicleId("id1") -> 10000.0
      )
    }
  }

}
