package beam.utils.mapsapi.googleapi

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http

import akka.stream.scaladsl.StreamConverters
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.Segment
import beam.utils.mapsapi.googleapi.GoogleAdapter._
import org.apache.commons.io.FileUtils
import play.api.libs.json.{JsArray, JsLookupResult, JsObject, JsValue, Json}

class GoogleAdapter(apiKey: String, outputResponseToFile: Option[Path] = None) extends AutoCloseable {
  private implicit val system: ActorSystem = ActorSystem()

  private val timeout: FiniteDuration = new FiniteDuration(5L, TimeUnit.SECONDS)

  def findRoutes(
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    mode: TravelModes.TravelMode = TravelModes.Driving,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint] = Set.empty
  ): Future[Seq[Route]] = {
    val url = buildUrl(apiKey, origin, destination, departureAt, mode, trafficModel, constraints)
    call(url).map(writeToFileIfSetup).map(toRoutes)
  }

  private def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  private def parseRoutes(jsRoutes: Seq[JsValue]): Seq[Route] = {
    jsRoutes.map { route =>
      val firstAndUniqueLeg = (route \ "legs").as[JsArray].value.head
      parseRoute(firstAndUniqueLeg.as[JsObject])
    }
  }

  private def writeToFileIfSetup(jsObject: JsObject): JsObject = {
    if (outputResponseToFile.isDefined) {
      FileUtils.writeStringToFile(outputResponseToFile.get.toFile, Json.prettyPrint(jsObject), StandardCharsets.UTF_8)
    }
    jsObject
  }

  private def toRoutes(jsObject: JsObject): Seq[Route] = {

    parseRoutes((jsObject \ "routes").as[JsArray].value)
  }

  private def parseRoute(jsObject: JsObject): Route = {
    val segments = parseSegments((jsObject \ "steps").as[JsArray].value)
    val distanceInMeter = (jsObject \ "distance" \ "value").as[Int]
    val durationInSeconds = (jsObject \ "duration" \ "value").as[Int]
    val startLocation = parseWgsCoordinate(jsObject \ "start_location")
    val endLocation = parseWgsCoordinate(jsObject \ "end_location")
    Route(startLocation, endLocation, distanceInMeter, durationInSeconds, segments)
  }

  private def parseStep(jsObject: JsObject): Segment = {
    Segment(
      coordinates = GooglePolylineDecoder.decode((jsObject \ "polyline" \ "points").as[String]),
      lengthInMeters = (jsObject \ "distance" \ "value").as[Int],
      durationInSeconds = Some((jsObject \ "duration" \ "value").as[Int])
    )
  }

  private def parseSegments(steps: Seq[JsValue]): Seq[Segment] = {
    steps.map { step =>
      parseStep(step.as[JsObject])
    }
  }

  private def parseWgsCoordinate(position: JsLookupResult) = {
    WgsCoordinate(
      latitude = (position \ "lat").as[Double],
      longitude = (position \ "lng").as[Double]
    )
  }

  override def close(): Unit = {
    Http().shutdownAllConnectionPools
      .andThen {
        case _ =>
          system.terminate()
      }
  }

}

object GoogleAdapter {

  private[googleapi] def buildUrl(
    apiKey: String,
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime,
    mode: TravelModes.TravelMode,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint]
  ): String = {
    // avoid=tolls|highways|ferries
    val originStr = s"${origin.latitude},${origin.longitude}"
    val destinationStr = s"${destination.latitude},${destination.longitude}"
    val params = Seq(
      s"mode=${mode.apiString}",
      "language=en",
      "units=metric",
      "alternatives=true",
      s"key=$apiKey",
      s"mode=${mode.apiString}",
      s"origin=$originStr",
      s"destination=$destinationStr",
      s"traffic_model=${trafficModel.apiString}",
      s"departure_time=${dateAsEpochSecond(departureAt)}",
    )
    val optionalParams = {
      if (constraints.isEmpty) Seq.empty
      else Seq(s"avoid=${constraints.map(_.apiName).mkString("|")}")
    }

    val baseUrl = "https://maps.googleapis.com/maps/api/directions/json"

    s"$baseUrl${(params ++ optionalParams).mkString("?", "&", "")}"
  }

  private def dateAsEpochSecond(ldt: LocalDateTime): Long = {
    ldt.toEpochSecond(ZoneOffset.UTC)
  }

}
