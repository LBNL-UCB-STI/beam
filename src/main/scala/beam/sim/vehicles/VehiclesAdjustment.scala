package beam.sim.vehicles
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.{BeamScenario, BeamServices}
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

trait VehiclesAdjustment extends LazyLogging {

  def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType]

  def sampleRideHailVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType]

}

object VehiclesAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val UNIFORM_ADJUSTMENT = "UNIFORM_ADJUSTMENT"

  def getVehicleAdjustment(beamScenario: BeamScenario): VehiclesAdjustment = {

    new UniformVehiclesAdjustment(beamScenario)
  }

}
