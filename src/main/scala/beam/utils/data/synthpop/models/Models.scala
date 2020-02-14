package beam.utils.data.synthpop.models

import scala.util.{Success, Try}

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

  object TazGeoId {

    def apply(input: String): TazGeoId = {
      require(
        input.length == 13,
        "Expecting to get TAZ GeoId which has the following format `st/cty/taz` => `ssccczzzzzzzz`"
      )
      // C56	CTPP Flow- TAZ-to-TAZ	st/cty/taz/st/cty/taz	C5600USssccczzzzzzzzssccczzzzzzzz
      val state = input.substring(0, 2)
      val county = input.substring(2, 5)
      val taz = input.substring(5)
      TazGeoId(state, county, taz)
    }
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
