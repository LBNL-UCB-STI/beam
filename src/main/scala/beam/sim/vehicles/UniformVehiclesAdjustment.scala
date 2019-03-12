package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.Coord

case class UniformVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {

  private val realDistribution: UniformRealDistribution = new UniformRealDistribution()
  realDistribution.reseedRandomGenerator(beamServices.beamConfig.matsim.modules.global.randomSeed)

  private val vehicleTypesAndProbabilitiesByCategory: Map[VehicleCategory, Array[(BeamVehicleType, Double)]] =
    beamServices.vehicleTypes.values.groupBy(_.vehicleCategory).map {
      case (cat, vehTypes) =>
        val probSum = vehTypes.map(_.sampleProbabilityWithinCategory).sum
        val cumulativeProbabilities = vehTypes
          .map(_.sampleProbabilityWithinCategory / probSum)
          .scan(0.0)(_ + _)
          .drop(1)
          .toList :+ 1.0
        val vehTypeWithProbability =
          vehTypes.zip(cumulativeProbabilities).map { case (vehType, prob) => (vehType, prob) }.toArray
        (cat, vehTypeWithProbability)
    }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType] = {
    val vehTypeWithProbability = vehicleTypesAndProbabilitiesByCategory(vehicleCategory)
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }
}
