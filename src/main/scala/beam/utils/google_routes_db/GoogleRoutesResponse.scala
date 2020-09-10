package beam.utils.google_routes_db

import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable

case class GoogleRoutesResponse(
  requestId: String,
  response: GoogleRoutes
)

case class GoogleRoutes(
  geocodedWaypoints: Option[Seq[GeocodedWaypoint]],
  routes: Seq[GoogleRoute],
  status: String
)

case class GeocodedWaypoint(
  geocoderStatus: Option[String]
  // Note: partial definition
)

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
    durationInTraffic: Option[ValueTxt],
    endAddress: String,
    endLocation: Coord,
    startAddress: Option[String],
    startLocation: Coord,
    steps: Seq[Step]
    // Note: partial definition
  )

  case class Step(
    distance: ValueTxt,
    duration: ValueTxt,
    endLocation: Coord,
    startLocation: Coord,
    travelMode: String
    // Note: partial definition
  )
}

object GoogleRoutesResponse {

  object Json extends LazyLogging {
    import io.circe._

    implicit val coordDecoder: Decoder[GoogleRoute.Coord] =
      Decoder.forProduct2("lat", "lng")(GoogleRoute.Coord.apply)

    implicit val boundsDecoder: Decoder[GoogleRoute.Bounds] =
      Decoder.forProduct2("northeast", "southwest")(GoogleRoute.Bounds.apply)

    implicit val valueTxtDecoder: Decoder[GoogleRoute.ValueTxt] =
      Decoder.forProduct2("value", "text")(GoogleRoute.ValueTxt.apply)

    implicit val stepDecoder: Decoder[GoogleRoute.Step] =
      Decoder.forProduct5(
        "distance",
        "duration",
        "end_location",
        "start_location",
        "travel_mode",
      )(GoogleRoute.Step.apply)

    implicit val legDecoder: Decoder[GoogleRoute.Leg] =
      Decoder.forProduct8(
        "distance",
        "duration",
        "duration_in_traffic",
        "end_address",
        "end_location",
        "start_address",
        "start_location",
        "steps"
      )(GoogleRoute.Leg.apply)

    implicit val googleRouteDecoder: Decoder[GoogleRoute] =
      Decoder.forProduct4(
        "bounds",
        "copyrights",
        "legs",
        "summary"
      )(GoogleRoute.apply)

    implicit val geocodedWaypointDecoder: Decoder[GeocodedWaypoint] =
      Decoder.forProduct1("geocoder_status")(GeocodedWaypoint.apply)

    implicit val googleRoutesDecoder: Decoder[GoogleRoutes] =
      Decoder.forProduct3(
        "geocoded_waypoints",
        "routes",
        "status"
      )(GoogleRoutes.apply)

    implicit val googleRoutesResponseDecoder: Decoder[GoogleRoutesResponse] =
      Decoder.forProduct2(
        "requestId",
        "response"
      )(GoogleRoutesResponse.apply)

    def parseGoogleapiResponsesJson(text: String): immutable.Seq[GoogleRoutesResponse] = {
      parser.decode[immutable.Seq[GoogleRoutesResponse]](text) match {
        case Right(json) => json
        case Left(e) =>
          val head = text.take(200).replaceAll("\\s+", "")
          logger.warn(s"Failed to parse GoogleRoutes (<$head...>): ${e.getMessage}")
          immutable.Seq.empty
      }
    }
  }
}
