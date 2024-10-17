package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import org.apache.commons.math3.distribution.UniformRealDistribution
import beam.utils.scenario.HouseholdId
import org.matsim.api.core.v01.Coord

case class UniformVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  private val vehicleTypesAndProbabilitiesByCategory: Map[(VehicleCategory, String), Array[(BeamVehicleType, Double)]] =
    beamScenario.vehicleTypes.values.groupBy(x => (x.vehicleCategory, matchCarUse(x.id.toString))).map {
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
    householdLocation: Coord,
    realDistribution: UniformRealDistributionEnhanced,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType] = {
    val vehTypeWithProbability = vehicleTypesAndProbabilitiesByCategory(vehicleCategory, "Usage Not Set")
    (1 to numVehicles).map { _ =>
      val newRand = realDistribution.sample()
      val (vehType, _) = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.get
      vehType
    }.toList
  }

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistributionEnhanced
  ): List[BeamVehicleType] = {
    val vehTypeWithProbability = vehicleTypesAndProbabilitiesByCategory.getOrElse(
      (vehicleCategory, "Ride Hail Vehicle"),
      vehicleTypesAndProbabilitiesByCategory(vehicleCategory, "Usage Not Set")
    )
    (1 to numVehicles).flatMap { _ =>
      val newRand = realDistribution.sample()
      val vehType = vehTypeWithProbability.find { case (_, prob) => prob >= newRand }.map(_._1)
      vehType
    }.toList
  }

  private def matchCarUse(vehicleTypeId: String): String = {
    vehicleTypeId.split("_").headOption match {
      case Some(vehicleTypePrefix)
          if beamScenario.beamConfig.beam.agentsim.agents.rideHail.managers
            .exists(managerConfig => managerConfig.initialization.procedural.vehicleTypePrefix == vehicleTypePrefix) =>
        "Ride Hail Vehicle"
      case _ => "Usage Not Set"
    }
  }
}
