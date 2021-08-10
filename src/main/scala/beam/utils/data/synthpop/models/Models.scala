package beam.utils.data.synthpop.models

object Models {
  sealed trait Gender

  object Gender {
    case object Male extends Gender
    case object Female extends Gender
  }

  case class State(value: String) extends AnyVal
  case class County(value: String) extends AnyVal

  abstract class GenericGeoId()

  case class PumaGeoId(state: State, puma: String) extends GenericGeoId {
    def asUniqueKey: String = s"${state.value}$puma"
  }

  object PumaGeoId {

    def fromString(s: String): PumaGeoId = {
      val state = s.substring(0, 2)
      val puma = s.substring(2)
      PumaGeoId(State(state), puma)
    }
  }

  case class PowPumaGeoId(state: State, puma: String) {
    def asUniqueKey: String = s"${state.value}$puma"
  }

  object PowPumaGeoId {

    def fromString(s: String): PowPumaGeoId = {
      val state = s.substring(0, 2)
      val puma = s.substring(2)
      PowPumaGeoId(State(state), puma)
    }
  }

  case class BlockGroupGeoId(state: State, county: County, tract: String, blockGroup: String) extends GenericGeoId {
    def asUniqueKey: String = s"${state.value}-${county.value}-$tract-$blockGroup"
  }

  case class TazGeoId(state: State, county: County, taz: String) extends GenericGeoId {
    def asUniqueKey: String = s"${state.value}${county.value}$taz"
  }

  object TazGeoId {

    def fromString(input: String): TazGeoId = {
      require(
        input.length == 13,
        "Expecting to get TAZ GeoId which has the following format `st/cty/taz` => `ssccczzzzzzzz`"
      )
      // C56	CTPP Flow- TAZ-to-TAZ	st/cty/taz/st/cty/taz	C5600USssccczzzzzzzzssccczzzzzzzz
      val state = input.substring(0, 2)
      val county = input.substring(2, 5)
      val taz = input.substring(5)
      TazGeoId(State(state), County(county), taz)
    }
  }

  case class Household(
    id: String,
    geoId: BlockGroupGeoId,
    numOfPersons: Int,
    numOfVehicles: Int,
    income: Double,
    numOfChildren: Int,
    numOfWorkers: Int
  ) {
    val fullId: String = s"${geoId.state.value}-${geoId.county.value}-${geoId.tract}-${geoId.blockGroup}:$id"
  }
  case class Person(id: String, age: Int, gender: Gender, householdId: String)
}
