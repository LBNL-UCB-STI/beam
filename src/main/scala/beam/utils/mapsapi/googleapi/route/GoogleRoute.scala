package beam.utils.mapsapi.googleapi.route

case class GoogleRoute(
  bounds: GoogleRoute.Bounds,
  copyrights: String,
  legs: Seq[GoogleRoute.Leg],
  summary: String
)

object GoogleRoute {

  case class Coord(lat: Double, lng: Double)

  case class ValueTxt(value: Int, text: String)

  case class Bounds(
    northeast: Coord,
    southwest: Coord
  )

  case class Leg(
    distance: ValueTxt,
    duration: ValueTxt,
    // https://developers.google.com/maps/documentation/directions/overview?_gac=1.187038170.1596465170.Cj0KCQjw6575BRCQARIsAMp-ksPk0sK6Ztey7UXWPBRyjP0slBRVw3msLAYU6PPEZRHdAQUQGbsDrI0aAgxvEALw_wcB&_ga=2.204378384.1892646518.1596465112-448741100.1596465112#optional-parameters
    // For requests where the travel mode is driving: You can specify the departure_time to receive a route
    // and trip duration (response field: duration_in_traffic) that take traffic conditions into account.
    // This option is only available if the request contains a valid API key, or a valid Google Maps Platform Premium Plan client ID and signature.
    // The departure_time must be set to the current time or some time in the future. It cannot be in the past.
    durationInTraffic: Option[ValueTxt],
    endAddress: String,
    endLocation: Coord,
    startAddress: Option[String],
    startLocation: Coord,
    steps: Seq[Step]
    // Note: partial definition
  )

  case class Polyline(
    points: String
  )

  case class Step(
    distance: ValueTxt,
    duration: ValueTxt,
    endLocation: Coord,
    startLocation: Coord,
    travelMode: String,
    polyline: Polyline
    // Note: partial definition
  )

  object Json {
    import io.circe._
    import io.circe.generic.semiauto._

    implicit val coordDecoder: Decoder[GoogleRoute.Coord] =
      deriveDecoder[GoogleRoute.Coord]

    implicit val coordEncoder: Encoder[GoogleRoute.Coord] =
      deriveEncoder[GoogleRoute.Coord]

    implicit val boundsDecoder: Decoder[GoogleRoute.Bounds] =
      deriveDecoder[GoogleRoute.Bounds]

    implicit val boundsEncoder: Encoder[GoogleRoute.Bounds] =
      deriveEncoder[GoogleRoute.Bounds]

    implicit val valueTxtDecoder: Decoder[GoogleRoute.ValueTxt] =
      deriveDecoder[GoogleRoute.ValueTxt]

    implicit val valueTxtEncoder: Encoder[GoogleRoute.ValueTxt] =
      deriveEncoder[GoogleRoute.ValueTxt]

    implicit val polylineDecoder: Decoder[Polyline] =
      deriveDecoder[Polyline]

    implicit val polylineEncoder: Encoder[Polyline] =
      deriveEncoder[Polyline]

    implicit val stepDecoder: Decoder[GoogleRoute.Step] =
      deriveDecoder[GoogleRoute.Step]

    implicit val stepEncoder: Encoder[GoogleRoute.Step] =
      deriveEncoder[GoogleRoute.Step]

    implicit val legDecoder: Decoder[GoogleRoute.Leg] =
      deriveDecoder[GoogleRoute.Leg]

    implicit val legEncoder: Encoder[GoogleRoute.Leg] =
      deriveEncoder[GoogleRoute.Leg]

    implicit val googleRouteDecoder: Decoder[GoogleRoute] =
      deriveDecoder[GoogleRoute]

    implicit val googleRouteEncoder: Encoder[GoogleRoute] =
      deriveEncoder[GoogleRoute]
  }
}
