package beam.utils.mapsapi.googleapi.route

case class GoogleRoutes(
  geocodedWaypoints: Option[Seq[GoogleRoutes.GeocodedWaypoint]],
  routes: Seq[GoogleRoute],
  status: String
)

object GoogleRoutes{

  case class GeocodedWaypoint(
    geocoderStatus: Option[String]
    // Note: partial definition
  )

  object Json {
    import io.circe._
    import io.circe.generic.semiauto._
    import GoogleRoute.Json._

    implicit val geocodedWaypointDecoder: Decoder[GeocodedWaypoint] =
      deriveDecoder[GeocodedWaypoint]

    implicit val geocodedWaypointEncoder: Encoder[GeocodedWaypoint] =
      deriveEncoder[GeocodedWaypoint]

    implicit val googleRoutesDecoder: Decoder[GoogleRoutes] =
      deriveDecoder[GoogleRoutes]

    implicit val googleRoutesEncoder: Encoder[GoogleRoutes] =
      deriveEncoder[GoogleRoutes]
  }
}

