package beam.utils.scenario.matsim
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}

class MatsimScenarioSource(val scenarioFolder: String, val rdr: MatsimScenarioReader) extends ScenarioSource {

  val fileExt: String = rdr.inputType.toFileExt

  val personFilePath: String = s"$scenarioFolder/population.$fileExt"
  val householdFilePath: String = s"$scenarioFolder/households.$fileExt"
  val planFilePath: String = s"$scenarioFolder/plans.$fileExt"

  override def getPersons: Iterable[PersonInfo] = {
    rdr.readPersonsFile(personFilePath)
  }
  override def getPlans: Iterable[PlanElement] = {
    rdr.readPlansFile(planFilePath)
  }
  override def getHousehold: Iterable[HouseholdInfo] = {
    rdr.readHouseholdsFile(householdFilePath)
  }
}
