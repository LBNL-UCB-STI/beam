package beam.utils.mapsapi.bingapi

import java.util.concurrent.TimeUnit

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.stream.scaladsl.StreamConverters
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

class BingAdapter(apiKey: String, actorSystem: Option[ActorSystem] = None) extends AutoCloseable {

  private implicit val system: ActorSystem = actorSystem.getOrElse(ActorSystem())
  private val timeout: FiniteDuration = new FiniteDuration(5L, TimeUnit.SECONDS)

  private[bingapi] def findPath(points: Seq[WgsCoordinate]): Future[Seq[SnappedPoint]] = {
    val pointsStr = points
      .map { p =>
        s"${p.latitude},${p.longitude}"
      }
      .mkString(";")

    val baseUrl = "http://dev.virtualearth.net/REST/v1/Routes/SnapToRoad"
    val params = Seq(
      s"points=$pointsStr",
      "interpolate=true",
      "includeSpeedLimit=true",
      "includeTruckSpeedLimit=false",
      "speedUnit=KPH",
      "travelMode=driving",
      s"key=$apiKey"
    )
    val url = s"$baseUrl${params.mkString("?", "&", "")}"
    println(url)
    call(url).map(toSnappedPoints)
  }

  def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  private def toSnappedPoints(jsObject: JsObject): Seq[SnappedPoint] = {
    val array = (jsObject \ "resourceSets").as[JsArray]
    val headResources = (array.head \ "resources").as[JsArray].head
    val snapped: JsArray = (headResources \ "snappedPoints").as[JsArray]
    val value: IndexedSeq[JsValue] = snapped.value
    value.map(x => toSnappedPoint(x.as[JsObject]))
  }

  private def toSnappedPoint(jsObject: JsObject): SnappedPoint = {
    val coordinate = jsObject \ "coordinate"
    SnappedPoint(
      coordinate = WgsCoordinate(
        latitude = (coordinate \ "latitude").as[Double],
        longitude = (coordinate \ "longitude").as[Double],
      ),
      name = (jsObject \ "name").as[String],
      speedLimitInKph = (jsObject \ "speedLimit").as[Double]
    )
  }

  override def close(): Unit = {
    implicit val timeOut: Timeout = new Timeout(20L, TimeUnit.SECONDS)
    Http().shutdownAllConnectionPools
      .andThen {
        case _ =>
          if (actorSystem.isEmpty) system.terminate()
      }

  }
}
