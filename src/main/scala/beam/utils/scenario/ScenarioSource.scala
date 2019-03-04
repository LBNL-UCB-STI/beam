package beam.utils.scenario

trait ScenarioSource {
  def getPersons: Iterable[PersonInfo]
  def getPlans: Iterable[PlanInfo]
  def getHousehold: Iterable[HouseholdInfo]
}
