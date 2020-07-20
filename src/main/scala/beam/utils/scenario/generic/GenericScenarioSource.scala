package beam.utils.scenario.generic

import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}

class GenericScenarioSource(val pathToHouseholds: String, val pathToPersonFile: String, val pathToPlans: String)
    extends ScenarioSource {
  override def getPersons: Iterable[PersonInfo] = {
    CsvPersonInfoReader.read(pathToPersonFile)
  }

  override def getPlans: Iterable[PlanElement] = {
    CsvPlanElementReader.read(pathToPlans)
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    CsvHouseholdInfoReader.read(pathToHouseholds)
  }
}
