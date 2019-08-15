package beam.utils.scenario

trait ScenarioSource {
  def getPersons: Iterable[PersonInfo]
  def getPlans: Iterable[PlanElement]
  def getHousehold: Iterable[HouseholdInfo]
  def getVehicles: Iterable[VehicleInfo] = Iterable()
}
