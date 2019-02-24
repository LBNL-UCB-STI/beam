package beam.utils.scenario

trait ScenarioReader {
  def inputType: InputType
  def readUnitsFile(path: String): Array[UnitInfo]
  def readParcelAttrFile(path: String): Array[ParcelAttribute]
  def readBuildingsFile(path: String): Array[BuildingInfo]
  def readPersonsFile(path: String): Array[PersonInfo]
  def readPlansFile(path: String): Array[PlanInfo]
  def readHouseholdsFile(path: String): Array[HouseholdInfo]
}
