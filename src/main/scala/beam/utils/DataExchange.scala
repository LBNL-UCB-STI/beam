package beam.utils

case class UnitInfo(unitId: String, buildingId: String)

case class ParcelAttribute(primaryId: String, x: Double, y: Double)

case class BuildingInfo(buildingId: String, parcelId: String)

case class PersonInfo(personId: String, householdId: String, rank: Int, age: Int)

case class PlanInfo(personId: String, planElement: String, activityType: Option[String], x: Option[Double], y: Option[Double], endTime: Option[Double], mode: Option[String])

case class HouseholdInfo(householdId: String, cars: Double, unitId: String, income: Double)