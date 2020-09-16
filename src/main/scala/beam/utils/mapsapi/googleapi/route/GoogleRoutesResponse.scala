package beam.utils.mapsapi.googleapi.route

import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable

case class GoogleRoutesResponse(
  requestId: String,
  departureLocalDateTime: String,
  response: GoogleRoutes
)

object GoogleRoutesResponse {

  object Json extends LazyLogging {
    import io.circe._
    import io.circe.generic.semiauto._
    import GoogleRoutes.Json._

    implicit val googleRoutesResponseDecoder: Decoder[GoogleRoutesResponse] =
      deriveDecoder[GoogleRoutesResponse]

    implicit val googleRoutesResponseEncoder: Encoder[GoogleRoutesResponse] =
      deriveEncoder[GoogleRoutesResponse]

    def parseGoogleapiResponsesJson(text: String): immutable.Seq[GoogleRoutesResponse] = {
      parser.decode[immutable.Seq[GoogleRoutesResponse]](text) match {
        case Right(json) => json
        case Left(e) =>
          val head = text.take(200).replaceAll("\\s+", "")
          logger.warn(s"Failed to parse GoogleRoutes (<$head...>): {}", e.getMessage)
          immutable.Seq.empty
      }
    }
  }
}
