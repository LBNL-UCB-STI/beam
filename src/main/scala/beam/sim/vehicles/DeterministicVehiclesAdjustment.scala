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

  private lazy val totalNumberOfVehicles =
    beamScenario.privateVehicles.values.groupBy(_.beamVehicleType.vehicleCategory).map(x => x._1 -> x._2.toList.length)

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
  }

  private def sampleAnyVehicle(
    vehicleCategory: VehicleCategory,
    realDistribution: UniformRealDistribution
  ): BeamVehicleType = {
    val indexToTake =
      (realDistribution.sample() * totalNumberOfVehicles.getOrElse(vehicleCategory, 0)).floor.toInt
    beamScenario.privateVehicles.values
      .filter(_.beamVehicleType.vehicleCategory == vehicleCategory)
      .drop(indexToTake)
      .head
      .beamVehicleType
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
    // In both of these cases it would be better to have an integer distribution rather than real, but this works fine
    if (numVehicles == 0) {
      List.empty[BeamVehicleType]
    } else {
      householdId match {
        case Some(hhId) =>
          val vehiclesToSampleFrom = householdIdToVehicleIds
            .getOrElse(hhId, Iterable.empty[VehicleInfo])
            .flatMap(vtid => beamScenario.vehicleTypes.get(Id.create(vtid.vehicleTypeId, classOf[BeamVehicleType])))
            .filter(_.vehicleCategory == vehicleCategory)
            .toList
          if (numVehicles == vehiclesToSampleFrom.length) {
            vehiclesToSampleFrom
          } else if (vehiclesToSampleFrom.isEmpty) {
            (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
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
        case _ => (0 until numVehicles).map(_ => sampleAnyVehicle(vehicleCategory, realDistribution)).toList
      }
    }

  }
}
