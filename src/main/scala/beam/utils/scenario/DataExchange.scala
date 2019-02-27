package beam.utils.scenario

sealed trait InputType {

  def toFileExt: String = this match {
    case InputType.Parquet => "parquet"
    case InputType.CSV     => "csv"
  }
}

object InputType {
  case object Parquet extends InputType
  case object CSV extends InputType

  def apply(inputType: String): InputType = {
    inputType match {
      case "csv"     => CSV
      case "parquet" => Parquet
      case x         => throw new IllegalStateException(s"There is no map from type '${inputType} to `InputType`")
    }
  }
}

case class UnitInfo(unitId: String, buildingId: String)

case class ParcelAttribute(primaryId: String, x: Double, y: Double)

case class BuildingInfo(buildingId: String, parcelId: String)

case class PersonInfo(personId: String, householdId: String, rank: Int, age: Int)

case class PlanInfo(
  personId: String,
  planElement: String,
  activityType: Option[String],
  x: Option[Double],
  y: Option[Double],
  endTime: Option[Double],
  mode: Option[String]
)

case class HouseholdInfo(householdId: String, cars: Double, income: Double, unitId: String, buildingId: String)
