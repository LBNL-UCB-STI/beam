package beam.sim.vehicles
import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Scenario}

trait VehiclesAdjustment extends LazyLogging {

  val beamServices: BeamServices

  def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType]

}

object VehiclesAdjustment {
  val DEFAULT_ADJUSTMENT = "DEFAULT_ADJUSTMENT"
  val UNIFORM_ADJUSTMENT = "UNIFORM_ADJUSTMENT"

  def getVehicleAdjustment(beamServices: BeamServices): VehiclesAdjustment = {

    new UniformVehiclesAdjustment(beamServices)
  }

}
