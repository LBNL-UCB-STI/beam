package beam.utils.scenario.matsim
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource, VehicleInfo}

class MatsimScenarioSource(val scenarioFolder: String, val rdr: MatsimScenarioReader) extends ScenarioSource {

  private val fileSuffix: String = rdr.inputType.toFileExt

  private def filePath(fileName:String) = s"$scenarioFolder/$fileName.$fileSuffix"

  override def getPersons: Iterable[PersonInfo] = {
    rdr.readPersonsFile(filePath("population"))
  }
  override def getPlans: Iterable[PlanElement] = {
    rdr.readPlansFile(filePath("plans"))
  }
  override def getHousehold: Iterable[HouseholdInfo] = {
    rdr.readHouseholdsFile(filePath("households"), getVehicles)
  }

  override lazy val getVehicles: Iterable[VehicleInfo] = {
    rdr.readVehiclesFile(filePath("vehicles"))
  }

}
