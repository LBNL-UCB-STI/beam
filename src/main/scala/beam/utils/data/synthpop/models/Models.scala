package beam.utils.data.synthpop.models

object Models {
  sealed trait Gender

  object Gender {
    case object Male extends Gender
    case object Female extends Gender
  }
  case class PumaGeoId(state: String, puma: String) {
    def asUniqueKey: String = s"${state}${puma}"
  }

  case class BlockGroupGeoId(state: String, county: String, tract: String, blockGroup: String) {
    def asUniqueKey: String = s"${state}${county}${tract}${blockGroup}"
  }

  case class Household(
    id: String,
    geoId: BlockGroupGeoId,
    numOfPersons: Int,
    numOfVehicles: Int,
    income: Double,
    numOfChildren: Int,
    numOfWorkers: Int
  )
  case class Person(age: Int, gender: Gender, householdId: String)
}
