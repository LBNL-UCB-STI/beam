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

  val vehicleTypesAndProbabilitiesByCategory =
    beamServices.vehicleTypes.values.groupBy(x => (x.vehicleCategory, matchCarUse(x.id.toString))).map { catAndType =>
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
      val newRand = randUnif.get
      vehicleTypesAndProbabilitiesByCategory((vehicleCategory, "Personal Vehicle")).find(_._2 >= newRand).get._1
    }.toList
    result
  }

  override def sampleVehicleTypesForCategory(
    numVehicles: Int,
    vehicleCategory: VehicleCategory
  ): List[BeamVehicleType] = {
    val result = Range(0, numVehicles).map { i =>
      val newRand = randUnif.get
      vehicleTypesAndProbabilitiesByCategory((vehicleCategory, "Ride Hail Vehicle")).find(_._2 >= newRand).get._1
    }.toList
    result
  }

  private def matchCarUse(vehicleTypeId: String): String = {
    vehicleTypeId.toString.split("_").headOption match {
      case Some("PV") => "Personal Vehicle"
      case Some("RH") => "Ride Hail Vehicle"
      case None       => "Usage not set"
      case _          => "Usage not set"
    }
  }
}
