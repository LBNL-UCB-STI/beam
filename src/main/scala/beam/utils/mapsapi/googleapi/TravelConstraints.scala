package beam.utils.mapsapi.googleapi

object TravelConstraints {
  sealed abstract class TravelConstraint(val apiName: String)
  case object AvoidTolls extends TravelConstraint("tolls")
  case object AvoidHighways extends TravelConstraint("highways")
  case object AvoidFerries extends TravelConstraint("ferries")
}
