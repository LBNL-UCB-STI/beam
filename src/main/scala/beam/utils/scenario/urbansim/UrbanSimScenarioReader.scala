package beam.utils.scenario.urbansim

import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.DataExchange._

trait UrbanSimScenarioReader {
  def inputType: InputType
  def readUnitsFile(path: String): Array[UnitInfo]
  def readParcelAttrFile(path: String): Array[ParcelAttribute]
  def readBuildingsFile(path: String): Array[BuildingInfo]
  def readPersonsFile(path: String): Array[PersonInfo]
  def readPlansFile(path: String): Array[PlanElement]
  def readHouseholdsFile(path: String): Array[HouseholdInfo]
}

object UrbanSimScenarioReader {

  def fixParcelId(rawParcelId: String): String = fixId(rawParcelId)

  def fixBuildingId(rawBuildingId: String): String = fixId(rawBuildingId)

  private def fixId(id: String): String = {
    if (id.indexOf(".") < 0)
      id
    else
      id.replaceAll("0*$", "").replaceAll("\\.$", "")
  }
}
