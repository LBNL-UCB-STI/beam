package beam.utils.scenario.matsim

import beam.utils.scenario._

trait BeamScenarioReader {
  def inputType: InputType
  def readPersonsFile(path: String): Array[PersonInfo]
  def readPlansFile(path: String): Array[PlanElement]
  def readHouseholdsFile(householdsPath: String, vehicles: Iterable[VehicleInfo]): Array[HouseholdInfo]
  def readVehiclesFile(vehiclesFilePath: String): Iterable[VehicleInfo]
}
