package beam.utils.mapsapi.googleapi

import java.io.{BufferedOutputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.pattern.ask
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.googleapi.GoogleAdapter._
import com.google.maps.DirectionsApi
import com.google.maps.model.DirectionsResult
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.io.{FileUtils, IOUtils}

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class GoogleAdapter(apiKey: String, outputResponseToFile: Option[Path] = None, actorSystem: Option[ActorSystem] = None)
    extends AutoCloseable
    with LazyLogging {
  private implicit val system: ActorSystem = actorSystem.getOrElse(ActorSystem())

  private val fileWriter = outputResponseToFile.map(path => system.actorOf(ResponseSaverActor.props(path.toFile)))

  def findRoutes[T](
    requests: Iterable[FindRouteRequest[T]]
  ): Future[Seq[FindRouteResult[T]]] = {
    val poolClientFlow = Http().cachedHostConnectionPoolHttps[FindRouteRequest[T]]("maps.googleapis.com")
    val results = Source(requests.toList)
      .map { request =>
        val url = buildUrl(apiKey, request)
        (HttpRequest(uri = url), request)
      }
      .via(poolClientFlow)
      .mapAsync(10) {

        case (Success(httpResponse), request) =>

          val maybeDirectionsApiResponseFuture: Future[Option[DirectionsApi.Response]] =
            httpResponse.entity.dataBytes.runReduce(_ ++ _).map { bs =>
              GoogleRoutesResponse.Json.decodeDirectionsApiResponse(bs.utf8String)
            }

          val maybeGRRFuture: Future[Option[GoogleRoutesResponse]] =
            maybeDirectionsApiResponseFuture.map { mbResp =>
              mbResp.flatMap { directionsApiResponse =>
                if (directionsApiResponse.successful()) {
                  Some(
                    GoogleRoutesResponse(
                      requestId = request.requestId,
                      departureLocalDateTime =
                        request.departureAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                      directionsResult = directionsApiResponse.getResult
                    )
                  )
                } else {
                  logger.error(
                    "Google DirectionsApi replied with error: {}",
                    directionsApiResponse.getError
                  )
                  None
                }
              }
            }

          maybeGRRFuture.foreach { mbResp =>
            mbResp.foreach { googleRoutesResponse =>
              fileWriter.foreach(_ ! googleRoutesResponse)
            }
          }

          maybeGRRFuture
            .map {
              case Some(resp) => Right(resp.directionsResult)
              case None => Left(new RuntimeException("Failed to acquire Google routes"))
            }
            .map { either: Either[Throwable, DirectionsResult] =>
              FindRouteResult(
                request.userObject,
                request.requestId,
                either
              )
            }

        case (Failure(throwable), request) =>
          Future.successful(
            FindRouteResult(
              request.userObject,
              request.requestId,
              Left(throwable)
            )
          )
      }
      .toMat(Sink.collection)(Keep.right)
      .run
    results
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
  case class FindRouteRequest[T](
    userObject: T,
    requestId: String = UUID.randomUUID().toString,
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    mode: TravelModes.TravelMode = TravelModes.Driving,
    trafficModel: TrafficModels.TrafficModel = TrafficModels.BestGuess,
    constraints: Set[TravelConstraints.TravelConstraint] = Set.empty
  )

  case class FindRouteResult[T](
    userObject: T,
    requestId: String,
    eitherResp: Either[Throwable, DirectionsResult]
  )

  private[googleapi] def buildUrl[T](
    apiKey: String,
    request: FindRouteRequest[T]
  ): String = {
    val FindRouteRequest(
      _,
      _,
      origin,
      destination,
      departureAt,
      mode,
      trafficModel,
      constraints
    ) = request

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
      // avoid=tolls|highways|ferries
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
    case resp: GoogleRoutesResponse =>
      val out = FileUtils.openOutputStream(file)
      val buffer = new BufferedOutputStream(out)
      val jsString = GoogleRoutesResponse.Json.encodeGoogleRoutesResponses(resp)
      IOUtils.write("[\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(jsString, buffer, StandardCharsets.UTF_8)
      context.become(saveIncoming(buffer))
    case ResponseSaverActor.CloseMsg =>
      sender() ! ResponseSaverActor.ClosedRsp
  }

  def saveIncoming(buffer: BufferedOutputStream): Actor.Receive = {
    case resp: GoogleRoutesResponse =>
      val jsString = GoogleRoutesResponse.Json.encodeGoogleRoutesResponses(resp)
      IOUtils.write(",\n", buffer, StandardCharsets.UTF_8)
      IOUtils.write(jsString, buffer, StandardCharsets.UTF_8)
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
