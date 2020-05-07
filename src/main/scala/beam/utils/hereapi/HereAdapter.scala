package beam.utils.hereapi

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import play.api.libs.json.{JsArray, JsObject, Json, JsValue}
import play.api.libs.ws.StandaloneWSClient
import play.api.libs.ws.ahc.StandaloneAhcWSClient

class HereAdapter(apiKey: String) extends AutoCloseable {
  import scala.concurrent.ExecutionContext.Implicits._

  private implicit val system: ActorSystem = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  private val wsClient: StandaloneWSClient = StandaloneAhcWSClient()

  def findPath(origin: WgsCoordinate, destination: WgsCoordinate): Future[HerePath] = {
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
    call(url, wsClient).map(toHerePath)
  }

  private def toSpan(x: JsValue): HereSpan = {
    HereSpan(
      lengthInMeters = (x \ "length").as[Int],
      speedLimitInKph = (x \ "speedLimit").asOpt[Double].map(_.toInt)
    )
  }

  private def toPolyLines(encodedPolyLines: String): Seq[WgsCoordinate] = {
    import collection.JavaConverters._

    val points = PolylineEncoderDecoder.decode(encodedPolyLines).asScala
    points.map { value =>
      WgsCoordinate(
        latitude = value.lat,
        longitude = value.lng
      )
    }.distinct.toList
  }

  def toHerePath(jsObject: JsObject): HerePath = {
    val firstRoute = (jsObject \ "routes").as[JsArray].value.head
    val firstSection = (firstRoute \ "sections").as[JsArray].value.head
    val polyLines: Seq[WgsCoordinate] = toPolyLines((firstSection \ "polyline").as[String])
    val spans = (firstSection \ "spans").as[JsArray].value.map(toSpan)
    HerePath(coordinates = polyLines, spans = spans)
  }

  def call(url: String, wsClient: StandaloneWSClient): Future[JsObject] = {
    val urlCall = wsClient
      .url(url)
      .addHttpHeaders("Content-Type" -> "application/json")

    urlCall.get().map { response =>
      Json.parse(response.body).as[JsObject]
    }
  }

  override def close(): Unit = {
    wsClient.close()
    system.terminate()
  }
}
