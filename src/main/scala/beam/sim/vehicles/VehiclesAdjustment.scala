package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import beam.utils.logging.ExponentialLazyLogging
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

trait VehiclesAdjustment extends ExponentialLazyLogging {

  def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType]

  def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType]

}

object VehiclesAdjustment {
  val UNIFORM_ADJUSTMENT = "UNIFORM"
  val INCOME_BASED_ADJUSTMENT = "INCOME_BASED"
  val SINGLE_TYPE = "SINGLE_TYPE"

  def getVehicleAdjustment(beamScenario: BeamScenario, adjustmentType: String = "", vehicleType: Option[String] = None): VehiclesAdjustment = {
    val adjustmentMethod = adjustmentType match{
      case "" => beamScenario.beamConfig.beam.agentsim.agents.vehicles.vehicleAdjustmentMethod
      case _ => adjustmentType
    }

    adjustmentMethod match {
      case UNIFORM_ADJUSTMENT      => UniformVehiclesAdjustment(beamScenario)
      case INCOME_BASED_ADJUSTMENT => IncomeBasedVehiclesAdjustment(beamScenario)
      case SINGLE_TYPE             => SingleTypeVehiclesAdjustment(beamScenario, vehicleType)
      case _                       => UniformVehiclesAdjustment(beamScenario)
    }

  }

}
