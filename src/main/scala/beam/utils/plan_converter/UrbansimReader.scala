package beam.utils.plan_converter

import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.{DataExchange, UrbanSimScenarioReader}

class UrbansimReader extends UrbanSimScenarioReader{
  override def inputType: InputType = InputType.CSV

  override def readUnitsFile(path: String): Array[DataExchange.UnitInfo] = Array.empty

  override def readParcelAttrFile(path: String): Array[DataExchange.ParcelAttribute] = Array.empty

  override def readBuildingsFile(path: String): Array[DataExchange.BuildingInfo] = Array.empty

  override def readPersonsFile(path: String): Array[DataExchange.PersonInfo] = ???

  override def readPlansFile(path: String): Array[DataExchange.PlanElement] = ???

  override def readHouseholdsFile(path: String): Array[DataExchange.HouseholdInfo] = ???
}
