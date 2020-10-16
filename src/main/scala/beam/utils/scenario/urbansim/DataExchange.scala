package beam.utils.scenario.urbansim

object DataExchange {
  case class UnitInfo(unitId: String, buildingId: String)

  case class ParcelAttribute(primaryId: String, x: Double, y: Double)

  case class BuildingInfo(buildingId: String, parcelId: String)

  case class PersonInfo(
    personId: String,
    householdId: String,
    rank: Int,
    age: Int,
    isFemale: Boolean,
    valueOfTime: Double,
    industry: Option[String]
  )

  case class PlanElement(
    personId: String,
    planElement: String,
    planElementIndex: Int,
    activityType: Option[String],
    x: Option[Double],
    y: Option[Double],
    endTime: Option[Double],
    mode: Option[String]
  )

  case class HouseholdInfo(householdId: String, cars: Int, income: Double, unitId: String, buildingId: String)
}
