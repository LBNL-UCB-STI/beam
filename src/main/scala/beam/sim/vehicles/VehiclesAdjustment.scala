package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import beam.utils.UniformRealDistributionEnhanced
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.scenario.{HouseholdId, VehicleInfo}
import org.matsim.api.core.v01.Coord

trait VehiclesAdjustment extends ExponentialLazyLogging {

  def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistributionEnhanced,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType]

  def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistributionEnhanced
  ): List[BeamVehicleType]

  /**
    * Samples a list of vehicle types for a freight carrier based on a probability distribution.
    *
    * This method is optimized for speed:
    *   1. Converts the input probability map into two parallel arrays:
    *        - `types`: vehicle types
    *        - `cumulative`: cumulative probability thresholds
    *   2. Normalizes probabilities to avoid floating-point drift.
    *   3. Uses **binary search** on the cumulative array for each random draw.
    *
    * Complexity:
    *   - Setup: O(n) where n = number of vehicle types
    *   - Sampling: O(numVehicles × log n)
    *
    * @param numVehicles Number of vehicles to sample
    * @param fleetDistribution Map of vehicle type -> probability share (does not need to sum to 1.0)
    * @param realDistribution Random number generator (UniformRealDistributionEnhanced)
    * @return List of sampled vehicle types (length = numVehicles)
    */
  def sampleVehicleTypesForCarrier(
    numVehicles: Int,
    fleetDistribution: Map[BeamVehicleType, Double],
    realDistribution: UniformRealDistributionEnhanced
  ): List[BeamVehicleType] = {

    // Early exit for empty distribution or invalid request
    if (fleetDistribution.isEmpty || numVehicles <= 0) {
      return List.empty
    }

    // --- Step 1: Build cumulative probability arrays for fast binary search ---
    // Using arrays avoids extra allocations and speeds up lookups
    val types = new Array[BeamVehicleType](fleetDistribution.size)
    val cumulative = new Array[Double](fleetDistribution.size)

    var i = 0
    var runningTotal = 0.0
    // Iterate once over the distribution to fill arrays
    fleetDistribution.foreach { case (vehType, share) =>
      runningTotal += share
      types(i) = vehType
      cumulative(i) = runningTotal
      i += 1
    }

    // --- Step 2: Normalize cumulative probabilities to exactly 1.0 ---
    // This avoids floating-point drift if shares don't sum perfectly
    val total = runningTotal
    var j = 0
    while (j < cumulative.length) {
      cumulative(j) /= total
      j += 1
    }

    // --- Step 3: Sampling using binary search ---
    // This makes each draw O(log n) instead of O(n) with .find
    val results = new Array[BeamVehicleType](numVehicles)
    var k = 0
    while (k < numVehicles) {
      val draw = realDistribution.sample() // uniform [0,1]
      // Binary search in cumulative array
      var low = 0
      var high = cumulative.length - 1
      while (low < high) {
        val mid = (low + high) >>> 1 // unsigned shift for /2
        if (draw <= cumulative(mid)) high = mid
        else low = mid + 1
      }
      results(k) = types(low)
      k += 1
    }

    // Convert to List once at the end
    results.toList
  }

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
