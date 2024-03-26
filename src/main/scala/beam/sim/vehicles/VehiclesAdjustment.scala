package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scenario.{HouseholdId, VehicleInfo}
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
    realDistribution: UniformRealDistribution,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType]

  def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType]

}

object VehiclesAdjustment extends ExponentialLazyLogging {
  val UNIFORM_ADJUSTMENT = "UNIFORM"
  val INCOME_BASED_ADJUSTMENT = "INCOME_BASED"
  val SINGLE_TYPE = "SINGLE_TYPE"
  val DETERMINISTIC = "DETERMINISTIC"

  def getVehicleAdjustment(
    beamScenario: BeamScenario,
    adjustmentType: String = "",
    vehicleType: Option[String] = None,
    householdIdToVehicleIdsOption: Option[Map[HouseholdId, Iterable[VehicleInfo]]] = None
  ): VehiclesAdjustment = {
    val adjustmentMethod = adjustmentType match {
      case "" => beamScenario.beamConfig.beam.agentsim.agents.vehicles.vehicleAdjustmentMethod
      case _  => adjustmentType
    }

    adjustmentMethod match {
      case UNIFORM_ADJUSTMENT      => UniformVehiclesAdjustment(beamScenario)
      case INCOME_BASED_ADJUSTMENT => IncomeBasedVehiclesAdjustment(beamScenario)
      case SINGLE_TYPE             => SingleTypeVehiclesAdjustment(beamScenario, vehicleType)
      case DETERMINISTIC =>
        householdIdToVehicleIdsOption match {
          case Some(householdIdToVehicleIds) => DeterministicVehiclesAdjustment(beamScenario, householdIdToVehicleIds)
          case _ =>
            logger.warn(
              "Cannot use DETERMINISTIC vehicle adjustment for shared vehicle fleets. Defaulting to " +
              "UNIFORM instead. To fix this change `initialization.procedural.vehicleAdjustmentMethod` in the config"
            )
            UniformVehiclesAdjustment(beamScenario)
        }
      case _ => UniformVehiclesAdjustment(beamScenario)
    }

  }

}
