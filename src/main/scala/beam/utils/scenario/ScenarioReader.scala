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

object ScenarioReader {
  def fixParcelId(rawParcelId: String): String = {
    if (rawParcelId.indexOf(".") < 0)
      rawParcelId
    else
      rawParcelId.replaceAll("0*$", "").replaceAll("\\.$", "")
  }

  def fixBuildingId(rawBuildingId: String): String = fixParcelId(rawBuildingId)
}