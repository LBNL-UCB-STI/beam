package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord
import probability_monad.Distribution

case class UniformVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {
  val randUnif = Distribution.uniform
  val probabilities = randUnif.sample(beamServices.vehicleTypes.size)
  val vehicleTypesAndProbabilitiesByCategory = beamServices.vehicleTypes.values.zip(probabilities).
    groupBy(_._1.vehicleCategory).map{ catAndTypeProb =>
      val probSum =  catAndTypeProb._2.map(_._2).sum
      val cumulProbs = catAndTypeProb._2.map(_._2/probSum).scan(0.0)(_ + _)
    (catAndTypeProb._1,catAndTypeProb._2.zip(cumulProbs).map(pair => (pair._1._1, pair._2)))
  }
  val vehicleTypesProbabilitiesByCategory = beamServices.vehicleTypes.values.groupBy(_.vehicleCategory)
  val (List(0.2,0.4,0.3,0.1))

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord
  ): List[BeamVehicleType] = {

    val result = Range(0, 100).map { i =>
      val newRand = randUnif.get
      vehicleTypesAndProbabilitiesByCategory(vehicleCategory).find(_._2 <= newRand).get._1
    }.toList

    result
  }
}
