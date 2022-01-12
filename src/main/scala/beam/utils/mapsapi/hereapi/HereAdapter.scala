package beam.utils.mapsapi.hereapi

import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits._

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.stream.scaladsl.StreamConverters
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

class HereAdapter(apiKey: String) extends AutoCloseable {

  private implicit val system: ActorSystem = ActorSystem()

  private val timeout: FiniteDuration = new FiniteDuration(5L, TimeUnit.SECONDS)

  private[hereapi] def findPath(origin: WgsCoordinate, destination: WgsCoordinate): Future[HerePath] = {
    val originStr = s"${origin.latitude},${origin.longitude}"
    val destinationStr = s"${destination.latitude},${destination.longitude}"
    val params = Seq(
      "units=metric",
      "return=polyline",
      "transportMode=car",
      "spans=speedLimit,length",
      s"origin=$originStr",
      s"destination=$destinationStr",
      s"apiKey=$apiKey"
    )
    val baseUrl = "https://router.hereapi.com/v8/routes"
    val url = s"$baseUrl${params.mkString("?", "&", "")}"

    call(url).map(toHerePath)
  }

  private def toSpan(x: JsValue): HereSpan = {
    HereSpan(
      offset = (x \ "offset").as[Int],
      lengthInMeters = (x \ "length").as[Int],
      speedLimitInKph = (x \ "speedLimit").asOpt[Double].map(_.toInt)
    )
  }

  private def toPolyLines(encodedPolyLines: String): Seq[WgsCoordinate] = {
    import collection.JavaConverters._

    val points = PolylineEncoderDecoder.decode(encodedPolyLines).asScala
    points
      .map { value =>
        WgsCoordinate(
          latitude = value.lat,
          longitude = value.lng
        )
      }
      .distinct
      .toList
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def toHerePath(jsObject: JsObject): HerePath = {
    val firstRoute = (jsObject \ "routes").as[JsArray].value.head
    val firstSection = (firstRoute \ "sections").as[JsArray].value.head
    val polyLines: Seq[WgsCoordinate] = toPolyLines((firstSection \ "polyline").as[String])
    val spans = (firstSection \ "spans").as[JsArray].value.map(toSpan)
    HerePath(coordinates = polyLines, spans = spans)
  }

  def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  override def close(): Unit = {
    Http().shutdownAllConnectionPools
      .andThen { case _ =>
        system.terminate()
      }
  }
}
