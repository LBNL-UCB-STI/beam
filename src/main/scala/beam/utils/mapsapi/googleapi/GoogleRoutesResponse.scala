package beam.utils.mapsapi.googleapi

import java.lang.reflect.Type
import java.time.{DayOfWeek, Instant, LocalTime, ZonedDateTime}
import java.{util => jul}

import com.google.gson.reflect.TypeToken
import com.google.gson.{FieldNamingPolicy, Gson, GsonBuilder}
import com.google.maps.internal._
import com.google.maps.model.PlaceDetails.Review.AspectRating.RatingType
import com.google.maps.model._
import com.google.maps.{DirectionsApi, GeolocationApi}
import com.typesafe.scalalogging.LazyLogging

case class GoogleRoutesResponse(
  requestId: String,
  departureLocalDateTime: String,
  directionsResult: DirectionsResult
)

object GoogleRoutesResponse {

  object Json extends LazyLogging {
    private val gson: Gson =
      new GsonBuilder()
        .registerTypeAdapter(classOf[ZonedDateTime], new ZonedDateTimeAdapter())
        .registerTypeAdapter(classOf[Distance], new DistanceAdapter())
        .registerTypeAdapter(classOf[Duration], new DurationAdapter())
        .registerTypeAdapter(classOf[Fare], new FareAdapter())
        .registerTypeAdapter(classOf[LatLng], new LatLngAdapter())
        .registerTypeAdapter(
          classOf[AddressComponentType],
          new SafeEnumAdapter[AddressComponentType](AddressComponentType.UNKNOWN)
        )
        .registerTypeAdapter(
          classOf[AddressType],
          new SafeEnumAdapter[AddressType](AddressType.UNKNOWN)
        )
        .registerTypeAdapter(
          classOf[TravelMode],
          new SafeEnumAdapter[TravelMode](TravelMode.UNKNOWN)
        )
        .registerTypeAdapter(
          classOf[LocationType],
          new SafeEnumAdapter[LocationType](LocationType.UNKNOWN)
        )
        .registerTypeAdapter(
          classOf[RatingType],
          new SafeEnumAdapter[RatingType](RatingType.UNKNOWN)
        )
        .registerTypeAdapter(classOf[DayOfWeek], new DayOfWeekAdapter())
        .registerTypeAdapter(classOf[PriceLevel], new PriceLevelAdapter())
        .registerTypeAdapter(classOf[Instant], new InstantAdapter())
        .registerTypeAdapter(classOf[LocalTime], new LocalTimeAdapter())
        .registerTypeAdapter(classOf[GeolocationApi.Response], new GeolocationResponseAdapter())
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    private val googleRoutesResponseCollectionType: Type =
      new TypeToken[jul.Collection[GoogleRoutesResponse]]() {}.getType

    def decodeDirectionsApiResponse(jsString: String): Option[DirectionsApi.Response] = {
      try {
        Some(gson.fromJson(jsString, classOf[DirectionsApi.Response]))
      } catch  {
        case e: Throwable =>
          val head = jsString.take(200).replaceAll("\\s+", "")
          logger.error(s"Failed to parse Google DirectionsApi.Response from <$head...>: {}", e)
          None
      }
    }

    def decodeGoogleRoutesResponses(jsString: String): Seq[GoogleRoutesResponse] = {
      import scala.collection.convert.ImplicitConversions._

      try {
        val collection = gson.fromJson[jul.Collection[GoogleRoutesResponse]](
          jsString,
          googleRoutesResponseCollectionType
        )
        collection.toSeq
      } catch {
        case e: Throwable =>
          val head = jsString.take(200).replaceAll("\\s+", "")
          logger.warn(s"Failed to parse GoogleRoutesResponses (<$head...>): {}", e.getMessage)
          Seq.empty
      }
    }

    def encodeGoogleRoutesResponses(grr: GoogleRoutesResponse): String = {
      gson.toJson(grr)
    }
  }
}
