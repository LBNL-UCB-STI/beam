package beam.sim.vehicles

import beam.agentsim.agents.Population
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.csv.readers
import beam.utils.scenario.VehicleInfo
import org.apache.commons.math3.distribution.UniformRealDistribution
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household

case class FromFileVehiclesAdjustment(beamScenario: BeamScenario) extends VehiclesAdjustment {

  var vehicles: Iterable[VehicleInfo] = List();
  var vehicleTypesMap: Map[Id[BeamVehicleType], BeamVehicleType] = Map();

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory.VehicleCategory,
    householdIncome: Double,
    householdSize: Int,
    householdPopulation: Population,
    householdLocation: Coord,
    realDistribution: UniformRealDistribution,
    householdId: Id[Household] = Id.create("", classOf[Household])
  ): List[BeamVehicleType] = {
    vehicles
      .filter(x => Id.create(x.householdId, classOf[Household]).equals(householdId))
      .map(x => Id.create(x.vehicleTypeId, classOf[BeamVehicleType]))
      .map(x => vehicleTypesMap(x))
      .toList
  }

  override def sampleVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory.VehicleCategory,
    realDistribution: UniformRealDistribution
  ): List[BeamVehicleType] = {
    List()
  }

  def readVechiclesFromFile() = {
    readers.BeamCsvScenarioReader.readVehiclesFile(
      beamScenario.beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath
    )
  }
}

object FromFileVehiclesAdjustment {

  def apply(beamScenario: BeamScenario) = new FromFileVehiclesAdjustment(beamScenario) {
    vehicles = readVechiclesFromFile()
    vehicleTypesMap = beamScenario.vehicleTypes.map(i => i._1 -> i._2).toMap
  }
}
