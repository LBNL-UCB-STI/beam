package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamScenario
import beam.utils.scenario.{HouseholdId, VehicleInfo}
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.{Coord, Id}

case class DeterministicVehiclesAdjustment(
  beamScenario: BeamScenario,
  householdIdToVehicleIds: Map[HouseholdId, Iterable[VehicleInfo]]
) extends VehiclesAdjustment {

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    throw new NoSuchMethodError("Can't use deterministic vehicle sample for non-household fleets")
  }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution,
    householdId: Option[HouseholdId]
  ): List[BeamVehicleType] = {
    householdId match {
      case Some(hhId) =>
        val vehiclesToSampleFrom = householdIdToVehicleIds
          .getOrElse(hhId, Iterable.empty[VehicleInfo])
          .flatMap(vtid => beamScenario.vehicleTypes.get(Id.create(vtid.vehicleTypeId, classOf[BeamVehicleType])))
          .toList
        if (numVehicles == vehiclesToSampleFrom.length) {
          vehiclesToSampleFrom
        } else {
          // TODO -- don't sample with replacement for numVehicles < the correct number of vehicles
          realDistribution
            .sample(numVehicles)
            .flatMap(x =>
              beamScenario.vehicleTypes.get(
                Id.create(vehiclesToSampleFrom((x * numVehicles).floor.toInt).id, classOf[BeamVehicleType])
              )
            )
            .toList
        }
      case _ => List.empty[BeamVehicleType]
    }
  }
}
