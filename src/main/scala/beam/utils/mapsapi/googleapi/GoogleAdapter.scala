package beam.utils.mapsapi.googleapi

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.{Segment, TransitPath}
import play.api.libs.json.{JsArray, JsObject, Json}

class GoogleAdapter(apiKey: String) extends AutoCloseable {
  private implicit val system: ActorSystem = ActorSystem()
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val timeout: FiniteDuration = new FiniteDuration(5L, TimeUnit.SECONDS)

  def findPath(origin: WgsCoordinate, destination: WgsCoordinate): Future[TransitPath] = {
    val originStr = s"${origin.latitude},${origin.longitude}"
    val destinationStr = s"${destination.latitude},${destination.longitude}"
    val params = Seq(
      "mode=driving",
      "language=en",
      "units=metric",
      s"origin=$originStr",
      s"destination=$destinationStr",
      s"key=$apiKey"
    )
    val baseUrl = "https://maps.googleapis.com/maps/api/directions/json"
    val url = s"$baseUrl${params.mkString("?", "&", "")}"

    call(url).map(toTransitPath)
  }

  def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  private def toTransitPath(jsObject: JsObject): TransitPath = {
    val routes = (jsObject \ "routes").as[JsArray].value.head
    val segments: IndexedSeq[Segment] = (routes \ "legs").as[JsArray].value.map { leg =>
      val startPoint = leg \ "start_location"
      val endPoint = leg \ "end_location"
      val start = WgsCoordinate(
        latitude = (startPoint \ "lat").as[Double],
        longitude = (startPoint \ "lng").as[Double]
      )
      val end = WgsCoordinate(
        latitude = (endPoint \ "lat").as[Double],
        longitude = (endPoint \ "lng").as[Double]
      )
      val distanceInMeters = (leg \ "distance" \ "value").as[Int]
      Segment(Seq(start, end), distanceInMeters, None)
    }
    TransitPath(segments)
  }

  override def close(): Unit = {
    Http().shutdownAllConnectionPools
      .andThen {
        case _ =>
          if (!materializer.isShutdown) materializer.shutdown()
          system.terminate()
      }
  }

}
