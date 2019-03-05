package beam.utils.scenario

case class PersonId(id: String) extends AnyVal

case class HouseholdId(id: String) extends AnyVal

case class PersonInfo(personId: PersonId, householdId: HouseholdId, rank: Int, age: Int)

case class PlanInfo(
  personId: PersonId,
  planElement: String,
  activityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  endTime: Option[Double],
  mode: Option[String]
)

case class HouseholdInfo(householdId: HouseholdId, cars: Double, income: Double, x: Double, y: Double)
