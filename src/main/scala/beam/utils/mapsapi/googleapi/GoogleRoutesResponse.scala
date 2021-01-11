package beam.utils.mapsapi.googleapi

import java.lang.reflect.Type
import java.time.{DayOfWeek, Instant, LocalTime, ZonedDateTime}
import java.{util => jul}

import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonWriter
import com.google.gson.{FieldNamingPolicy, Gson, GsonBuilder}
import com.google.maps.internal._
import com.google.maps.model.OpeningHours.Period.OpenClose
import com.google.maps.model.{Unit => _, _}
import com.google.maps.model.PlaceDetails.Review.AspectRating.RatingType
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
        .registerTypeAdapter(classOf[ZonedDateTime], GsonAdapters.zonedDateTimeAdapter)
        .registerTypeAdapter(classOf[Distance], GsonAdapters.distanceAdapter)
        .registerTypeAdapter(classOf[Duration], GsonAdapters.durationAdapter)
        .registerTypeAdapter(classOf[Fare], GsonAdapters.fareAdapter)
        .registerTypeAdapter(classOf[LatLng], GsonAdapters.latLngAdapter)
        .registerTypeAdapter(classOf[AddressComponentType], GsonAdapters.addressComponentTypeAdapter)
        .registerTypeAdapter(classOf[AddressType], GsonAdapters.addressTypeAdapter)
        .registerTypeAdapter(classOf[TravelMode], GsonAdapters.travelModeAdapter)
        .registerTypeAdapter(classOf[LocationType], GsonAdapters.locationTypeAdapter)
        .registerTypeAdapter(classOf[RatingType], GsonAdapters.ratingTypeAdapter)
        .registerTypeAdapter(classOf[DayOfWeek], GsonAdapters.dayOfWeekAdapter)
        .registerTypeAdapter(classOf[PriceLevel], GsonAdapters.priceLevelAdapter)
        .registerTypeAdapter(classOf[Instant], GsonAdapters.instantAdapter)
        .registerTypeAdapter(classOf[LocalTime], GsonAdapters.localTimeAdapter)
        .registerTypeAdapter(classOf[GeolocationApi.Response], GsonAdapters.geolocationResponseAdapter)
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .setPrettyPrinting()
        .create()

    private val googleRoutesResponseCollectionType: Type =
      new TypeToken[jul.Collection[GoogleRoutesResponse]]() {}.getType

    def decodeDirectionsApiResponse(jsString: String): Option[DirectionsApi.Response] = {
      try {
        Some(gson.fromJson(jsString, classOf[DirectionsApi.Response]))
      } catch {
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

  private[this] object GsonAdapters extends LazyLogging {

    val zonedDateTimeAdapter: ZonedDateTimeAdapter =
      new ZonedDateTimeAdapter() {
        override def write(writer: JsonWriter, value: ZonedDateTime): Unit = {
          if (value != null) {
            logger.debug("zonedDateTimeAdapter writes null for {}", value)
          }
          writer.nullValue()
        }
      }

    val distanceAdapter: DistanceAdapter =
      new DistanceAdapter() {
        override def write(writer: JsonWriter, value: Distance): Unit = {
          writer
            .beginObject()
            .name("text")
            .value(value.humanReadable)
            .name("value")
            .value(value.inMeters)
            .endObject()
        }
      }

    val durationAdapter: DurationAdapter =
      new DurationAdapter() {
        override def write(writer: JsonWriter, value: Duration): Unit = {
          writer
            .beginObject()
            .name("text")
            .value(value.humanReadable)
            .name("value")
            .value(value.inSeconds)
            .endObject()
        }
      }

    val fareAdapter: FareAdapter =
      new FareAdapter() {
        override def write(out: JsonWriter, value: Fare): Unit = {
          if (value != null) {
            logger.debug("fareAdapter writes null for {}", value)
          }
          out.nullValue()
        }
      }

    val latLngAdapter: LatLngAdapter =
      new LatLngAdapter() {
        override def write(out: JsonWriter, value: LatLng): Unit = {
          out
            .beginObject()
            .name("lat")
            .value(value.lat)
            .name("lng")
            .value(value.lng)
            .endObject()
        }
      }

    val addressComponentTypeAdapter: SafeEnumAdapter[AddressComponentType] =
      new SafeEnumAdapter[AddressComponentType](AddressComponentType.UNKNOWN) {
        override def write(out: JsonWriter, value: AddressComponentType): Unit = {
          if (value != null) out.value(value.toCanonicalLiteral) else out.nullValue()
        }
      }

    val addressTypeAdapter: SafeEnumAdapter[AddressType] =
      new SafeEnumAdapter[AddressType](AddressType.UNKNOWN) {
        override def write(out: JsonWriter, value: AddressType): Unit = {
          if (value != null) out.value(value.toCanonicalLiteral) else out.nullValue()
        }
      }

    val travelModeAdapter: SafeEnumAdapter[TravelMode] =
      new SafeEnumAdapter[TravelMode](TravelMode.UNKNOWN) {
        override def write(out: JsonWriter, value: TravelMode): Unit = {
          if (value != null) out.value(value.name()) else out.nullValue()
        }
      }

    val locationTypeAdapter: SafeEnumAdapter[LocationType] =
      new SafeEnumAdapter[LocationType](LocationType.UNKNOWN) {
        override def write(out: JsonWriter, value: LocationType): Unit = {
          if (value != null) out.value(value.name()) else out.nullValue()
        }
      }

    val ratingTypeAdapter: SafeEnumAdapter[RatingType] =
      new SafeEnumAdapter[RatingType](RatingType.UNKNOWN) {
        override def write(out: JsonWriter, value: RatingType): Unit = {
          if (value != null) out.value(value.name()) else out.nullValue()
        }
      }

    val dayOfWeekAdapter: DayOfWeekAdapter =
      new DayOfWeekAdapter() {
        override def write(writer: JsonWriter, value: OpenClose.DayOfWeek): Unit = {
          if (value != null) writer.value(value.ordinal()) else writer.nullValue()
        }
      }

    val priceLevelAdapter: PriceLevelAdapter =
      new PriceLevelAdapter() {
        override def write(writer: JsonWriter, value: PriceLevel): Unit = {
          if (value != null) writer.value(value.ordinal()) else writer.nullValue()
        }
      }

    val instantAdapter: InstantAdapter =
      new InstantAdapter() {
        override def write(out: JsonWriter, value: Instant): Unit = {
          if (value != null) {
            logger.debug("instantAdapter writes null for {}", value)
          }
          out.nullValue()
        }
      }

    val localTimeAdapter: LocalTimeAdapter =
      new LocalTimeAdapter() {
        override def write(out: JsonWriter, value: LocalTime): Unit = {
          if (value != null) {
            logger.debug("instantAdapter writes null for {}", value)
          }
          out.nullValue()
        }
      }

    val geolocationResponseAdapter: GeolocationResponseAdapter =
      new GeolocationResponseAdapter() {
        override def write(out: JsonWriter, value: GeolocationApi.Response): Unit = {
          if (value != null) {
            logger.debug("GeolocationResponseAdapter writes null for {}", value)
          }
          out.nullValue()
        }
      }
  }
}
