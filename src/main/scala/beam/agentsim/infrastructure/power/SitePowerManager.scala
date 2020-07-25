package beam.agentsim.infrastructure.power

import beam.agentsim.agents.vehicles.BeamVehicle
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

class SitePowerManager(privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle]) {
  import PowerController._
  import SitePowerManager._

  // TODO all methods return stub values
  def getPowerOverPlanningHorizon: PowerOverPlanningHorizon = 100.0

  def replanHorizon(physicalBounds: PhysicalBounds): Unit = {}

  def getChargingPlanPerVehicle: ChargingPlanPerVehicle =
    privateVehicles.map {
      case (_, v) => (v, 50.0)
    }
}

object SitePowerManager {
  type PowerOverPlanningHorizon = Double
  type RequiredEnergy = Double
  type ChargingPlanPerVehicle = TrieMap[BeamVehicle, RequiredEnergy]
}
