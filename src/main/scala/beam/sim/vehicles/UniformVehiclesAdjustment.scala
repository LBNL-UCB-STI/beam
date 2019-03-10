package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord
import scala.util.Random

case class UniformVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {

  val vehicleTypesAndProbabilitiesByCategory = beamServices.vehicleTypes.values.groupBy(_.vehicleCategory).map {
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

    val result = Range(0, numVehicles).map { i =>
      val newRand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
      vehicleTypesAndProbabilitiesByCategory(vehicleCategory).find(_._2 >= newRand.nextDouble()).get._1
    }.toList
    result
  }
}
