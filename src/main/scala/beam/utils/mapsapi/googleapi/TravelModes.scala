package beam.utils.mapsapi.googleapi

object TravelModes {

  sealed abstract class TravelMode(val apiString: String)
  case object Driving extends TravelMode("driving")
  case object Walking extends TravelMode("walking")
  case object Bicycling extends TravelMode("bicycling")
  case object Transit extends TravelMode("transit")

}
