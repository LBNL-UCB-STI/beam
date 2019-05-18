package beam.utils.scenario.urbansim

private[urbansim] object DataExchange {
  case class UnitInfo(unitId: String, buildingId: String)

  case class ParcelAttribute(primaryId: String, x: Double, y: Double)

  case class BuildingInfo(buildingId: String, parcelId: String)

  case class PersonInfo(personId: String, householdId: String, rank: Int, age: Int)

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

  case class HouseholdInfo(householdId: String, cars: Double, income: Double, unitId: String, buildingId: String)
}
