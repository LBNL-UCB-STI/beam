package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import beam.utils.Sampler
import org.matsim.api.core.v01.Coord

case class UniformVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {

  private val sampler = new Sampler(beamServices.beamConfig.matsim.modules.global.randomSeed)

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
  ): List[BeamVehicleType] = {
    val values: Iterable[(BeamVehicleType, Double)] = beamServices.vehicleTypes.values
      .filter(_.vehicleCategory == vehicleCategory)
      .map(e => (e, e.sampleProbabilityWithinCategory))
    sampler.uniformSample[BeamVehicleType](numVehicles, values).toList
  }

}
