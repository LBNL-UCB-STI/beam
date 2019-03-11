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

  private val vehicleTypesAndProbabilitiesByCategory = beamServices.vehicleTypes.values.groupBy(_.vehicleCategory).map {
    catAndType =>
      val probSum = catAndType._2.map(_.sampleProbabilityWithinCategory).sum
      val cumulProbs = catAndType._2
        .map(_.sampleProbabilityWithinCategory / probSum)
        .scan(0.0)(_ + _)
        .drop(1)
        .toList :+ 1.0
      (catAndType._1, catAndType._2.zip(cumulProbs).map(pair => (pair._1, pair._2)))
  }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType] = {

    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      vehicleTypesAndProbabilitiesByCategory(vehicleCategory).find(_._2 >= newRand).get._1
    }.toList
  }
}
