package beam.utils.scenario.matsim

import beam.sim.config.BeamConfig
import beam.utils.scenario._

class BeamScenarioSource(beamConfig: BeamConfig, rdr: BeamScenarioReader) extends ScenarioSource {
  private val configAgents = beamConfig.beam.agentsim.agents

  override def getPersons: Iterable[PersonInfo] = {
    rdr.readPersonsFile(configAgents.plans.inputPersonAttributesFilePath)
  }

  override def getPlans: Iterable[PlanElement] = {
    rdr.readPlansFile(configAgents.plans.inputPlansFilePath)
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    rdr.readHouseholdsFile(configAgents.households.inputFilePath, getVehicles)
  }

  override lazy val getVehicles: Iterable[VehicleInfo] = {
    rdr.readVehiclesFile(configAgents.vehicles.vehiclesFilePath)
  }

}
