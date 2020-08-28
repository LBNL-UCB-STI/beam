package beam.utils.mapsapi.googleapi

import java.io.{BufferedOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source, StreamConverters}
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.Segment
import beam.utils.mapsapi.googleapi.GoogleAdapter._
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.{FileUtils, IOUtils}
import play.api.libs.json._

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class GoogleAdapter(apiKey: String, outputResponseToFile: Option[Path] = None, actorSystem: Option[ActorSystem] = None)
    extends AutoCloseable
    with LazyLogging {
  private implicit val system: ActorSystem = actorSystem.getOrElse(ActorSystem())

  private val fileWriter = outputResponseToFile.map(path => system.actorOf(ResponseSaverActor.props(path.toFile)))

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

  def findRoutes[T](
    requests: Iterable[RouteRequest[T]]
  ): Future[IndexedSeq[(Either[Throwable, Seq[Route]], T)]] = {
    val poolClientFlow = Http().cachedHostConnectionPoolHttps[RouteRequest[T]]("maps.googleapis.com")
    val results = Source(requests.toList)
      .map { r =>
        val url = buildUrl(apiKey, r.origin, r.destination, r.departureAt, r.mode, r.trafficModel, r.constraints)
        (HttpRequest(uri = url), r)
      }
      .via(poolClientFlow)
      .mapAsync(10) {
        case (Success(httpResponse), rr) =>
          parseResponse(httpResponse)
            .map(writeToFileIfSetup)
            .map(toRoutes)
            .map(routes => (Right(routes), rr.userObject))
        case (Failure(throwable), rr) => Future.successful(Left(throwable), rr.userObject)
      }
      .toMat(Sink.collection)(Keep.right)
      .run
    results
  }

  private def call(url: String): Future[JsObject] = {
    val httpRequest = HttpRequest(uri = url)
    val responseFuture: Future[HttpResponse] = Http().singleRequest(httpRequest)
    responseFuture.map { response =>
      val inputStream = response.entity.dataBytes.runWith(StreamConverters.asInputStream(timeout))
      Json.parse(inputStream).as[JsObject]
    }
  }

  private def parseResponse(response: HttpResponse) = {
    val reduced = response.entity.dataBytes.runReduce(_ ++ _)
    reduced.map { bs =>
      val jsObject = Json.parse(bs.iterator.asInputStream).as[JsObject]
      val status = (jsObject \ "status").asOpt[String].getOrElse("no status field found")
      if (status != "OK") {
        val errorMessage = (jsObject \ "error_message").asOpt[String].getOrElse("no error message provided")
        logger.error("Response status is not OK: {}, error message: {}", status, errorMessage)
      }
      jsObject
    }
  }

  private def parseRoutes(jsRoutes: Seq[JsValue]): Seq[Route] = {
    jsRoutes.map { route =>
      val firstAndUniqueLeg = (route \ "legs").as[JsArray].value.head
      parseRoute(firstAndUniqueLeg.as[JsObject])
    }
  }

  private def writeToFileIfSetup(jsObject: JsObject): JsObject = {
    fileWriter.foreach(_ ! jsObject)
    jsObject
  }

  private def toRoutes(jsObject: JsObject): Seq[Route] = {
    (jsObject \ "status") match {
      case JsDefined(value) =>
        if (value != JsString("OK")) {
          val error = jsObject \ "error_message"
          logger.error(s"Google route request failed. Status: ${value}, error: $error")
        }
      case undefined: JsUndefined =>
    }
    parseRoutes((jsObject \ "routes").as[JsArray].value)
  }

  private def parseRoute(jsObject: JsObject): Route = {
    val segments = parseSegments((jsObject \ "steps").as[JsArray].value)
    val distanceInMeter = (jsObject \ "distance" \ "value").as[Int]
    val durationInSeconds = (jsObject \ "duration" \ "value").as[Int]
    // https://developers.google.com/maps/documentation/directions/overview?_gac=1.187038170.1596465170.Cj0KCQjw6575BRCQARIsAMp-ksPk0sK6Ztey7UXWPBRyjP0slBRVw3msLAYU6PPEZRHdAQUQGbsDrI0aAgxvEALw_wcB&_ga=2.204378384.1892646518.1596465112-448741100.1596465112#optional-parameters
    // For requests where the travel mode is driving: You can specify the departure_time to receive a route
    // and trip duration (response field: duration_in_traffic) that take traffic conditions into account.
    // This option is only available if the request contains a valid API key, or a valid Google Maps Platform Premium Plan client ID and signature.
    // The departure_time must be set to the current time or some time in the future. It cannot be in the past.
    val durationInTrafficSeconds = (jsObject \ "duration_in_traffic" \ "value").as[Int]
    val startLocation = parseWgsCoordinate(jsObject \ "start_location")
    val endLocation = parseWgsCoordinate(jsObject \ "end_location")
    Route(startLocation, endLocation, distanceInMeter, durationInSeconds, durationInTrafficSeconds, segments)
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
    implicit val timeOut: Timeout = new Timeout(20L, TimeUnit.SECONDS)
    fileWriter.foreach { ref =>
      val closed = ref ? ResponseSaverActor.CloseMsg
      Try(Await.result(closed, timeOut.duration))
      ref ! PoisonPill
    }
    Http().shutdownAllConnectionPools
      .andThen {
        case _ =>
          if (actorSystem.isEmpty) system.terminate()
      }
  }

}

object GoogleAdapter {
  case class RouteRequest[T](
    userObject: T,
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    mode: TravelModes.TravelMode = TravelModes.Driving,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint] = Set.empty
  )

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

class ResponseSaverActor(file: File) extends Actor {
  override def receive: Receive = {
    case jsObject: JsObject =>
      val out = FileUtils.openOutputStream(file)
      val buffer = new BufferedOutputStream(out)
      IOUtils.write("[\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(Json.prettyPrint(jsObject), buffer, StandardCharsets.UTF_8)
      context.become(saveIncoming(buffer))
    case ResponseSaverActor.CloseMsg =>
      sender() ! ResponseSaverActor.ClosedRsp
  }

  def saveIncoming(buffer: BufferedOutputStream): Actor.Receive = {
    case jsObject: JsObject =>
      IOUtils.write(",\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(Json.prettyPrint(jsObject), buffer, StandardCharsets.UTF_8)
    case ResponseSaverActor.CloseMsg =>
      IOUtils.write("\n]", buffer, StandardCharsets.UTF_8)
      buffer.close()
      sender() ! ResponseSaverActor.ClosedRsp
  }

}

object ResponseSaverActor {
  object CloseMsg
  object ClosedRsp

  def props(file: File): Props = {
    Props(new ResponseSaverActor(file))
  }
}
