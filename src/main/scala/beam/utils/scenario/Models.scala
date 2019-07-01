package beam.utils.scenario

case class PersonId(id: String) extends AnyVal

case class HouseholdId(id: String) extends AnyVal

case class PersonInfo(
  personId: PersonId,
  householdId: HouseholdId,
  rank: Int,
  age: Int,
  isFemale: Boolean,
  valueOfTime: Double
)

case class PlanElement(
  personId: PersonId,
  planIndex: Int = 0,
  planElementType: String,
  planElementIndex: Int,
  activityType: Option[String],
  activityLocationX: Option[Double],
  activityLocationY: Option[Double],
  activityEndTime: Option[Double],
  legMode: Option[String]
)

case class HouseholdInfo(householdId: HouseholdId, cars: Int, income: Double, locationX: Double, locationY: Double)

case class VehicleInfo(vehicleId: String, vehicleTypeId: String, householdId: String)
