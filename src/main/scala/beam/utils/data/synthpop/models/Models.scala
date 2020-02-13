package beam.utils.data.synthpop.models

object Models {
  sealed trait Gender

  object Gender {
    case object Male extends Gender
    case object Female extends Gender
  }
  case class TractGeoId(state: String, county: String, tract: String) {
    def asUniqueKey: String = s"${state}${county}${tract}"
  }

  case class TazGeoId(state: String, county: String, taz: String) {
    def asUniqueKey: String = s"${state}${county}${taz}"
  }

  case class Household(
    id: String,
    geoId: TractGeoId,
    numOfPersons: Int,
    numOfVehicles: Int,
    income: Double,
    numOfChildren: Int,
    numOfWorkers: Int
  )
  case class Person(age: Int, gender: Gender, householdId: String)
}
