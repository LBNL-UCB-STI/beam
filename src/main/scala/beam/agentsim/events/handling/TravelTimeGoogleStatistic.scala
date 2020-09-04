package beam.agentsim.events.handling

import java.nio.file.Paths
import java.time.{DayOfWeek, LocalDate, LocalDateTime, LocalTime}
import java.util.concurrent.TimeUnit
import java.util.{Objects, UUID}

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.using
import beam.utils.csv.CsvWriter
import beam.utils.mapsapi.googleapi.GoogleAdapter.{FindRouteRequest, FindRouteResult}
import beam.utils.mapsapi.googleapi.TravelConstraints.{AvoidTolls, TravelConstraint}
import beam.utils.mapsapi.googleapi.{GoogleAdapter, Route}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.util.{Random, Try}

/**
  *
  * @author Dmitry Openkov
  */
class TravelTimeGoogleStatistic(
  cfg: BeamConfig.Beam.Calibration.Google.TravelTimes,
  actorSystem: ActorSystem,
  geoUtils: GeoUtils
) extends BasicEventHandler
    with IterationEndsListener
    with LazyLogging {

  private val acc = mutable.ListBuffer.empty[PathTraversalEvent]
  private val apiKey = System.getenv("GOOGLE_API_KEY")
  if (cfg.enable && apiKey == null)
    logger.warn("google api key is empty")
  private val queryDate = getQueryDate(cfg.queryDate)

  private val enabled = cfg.enable && apiKey != null
  private val constraints: Set[TravelConstraint] = if (cfg.tolls) Set.empty else Set(AvoidTolls)

  override def handleEvent(event: Event): Unit = {
    if (enabled) {
      event match {
        case pte: PathTraversalEvent if pte.mode == BeamMode.CAR && pte.legLength >= cfg.minDistanceInMeters =>
          acc += pte
        case _ =>
      }
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    if (enabled
        && cfg.iterationInterval > 0
        && event.getIteration % cfg.iterationInterval == 0) {
      logger.info(
        "Executing google API call for iteration #{}, query date = {}",
        event.getIteration,
        queryDate.toLocalDate
      )
      val numEventsPerHour = Math.max(1, cfg.numDataPointsOver24Hours / 24)
      val byHour: Map[Int, ListBuffer[PathTraversalEvent]] = acc.groupBy(_.departureTime % 3600).filter {
        case (hour, _) => hour < 24
      }
      val events: immutable.Iterable[PathTraversalEvent] = byHour
        .flatMap {
          case (_, events) => getAppropriateEvents(events, numEventsPerHour)
        }
      logger.info("Number of events: {}", events.size)

      val controller = event.getServices.getControlerIO
      val responsePath = controller.getIterationFilename(event.getIteration, "maps.googleapi.responses.json")
      val adapter = new GoogleAdapter(apiKey, Some(Paths.get(responsePath)), Some(actorSystem))
      val result = using(adapter) { adapter =>
        queryGoogleAPI(events, adapter)
      }.sortBy(
        ec => (ec.event.departureTime, ec.event.vehicleId, ec.route.durationInTrafficSeconds)
      )
      val filePath = controller.getIterationFilename(event.getIteration, "googleTravelTimeEstimation.csv")
      val num = writeToCsv(result, filePath)
      logger.info(s"Saved $num routes to $filePath")
    }
  }

  private def queryGoogleAPI(events: Iterable[PathTraversalEvent], adapter: GoogleAdapter): Seq[EventContainer] = {
    val containersFuture = adapter.findRoutes(events.map(toRouteRequest))
      .map(_.flatMap { result =>
        val FindRouteResult(event, requestId, eitherRoutes) = result

        eitherRoutes match {
          case Left(e) =>
            logger.error(s"Error when calling google API for $event", e)
            Seq.empty[EventContainer]

          case Right(routes) =>
            routes.map { route =>
              EventContainer(event, requestId, route)
            }
        }
      })

    Await.result(containersFuture, 10.minutes)
  }

  private def writeToCsv(seq: Seq[EventContainer], filePath: String): Int = {
    import scala.language.implicitConversions
    val formatter = new java.text.DecimalFormat("#.########")
    implicit def doubleToString(x: Double): String = formatter.format(x)
    implicit def intToString(x: Int): String = x.toString

    val headers = Vector(
      "requestId",
      "vehicleId",
      "vehicleType",
      "departureTime",
      "originLat",
      "originLng",
      "destLat",
      "destLng",
      "simTravelTime",
      "googleTravelTime",
      "googleTravelTimeWithTraffic",
      "euclideanDistanceInMeters",
      "legLength",
      "googleDistance"
    )
    using(new CsvWriter(filePath, headers)) { csvWriter =>
      seq
        .map(
          ec =>
            Vector[String](
              ec.requestId,
              Objects.toString(ec.event.vehicleId),
              ec.event.vehicleType,
              ec.event.departureTime,
              ec.event.startY,
              ec.event.startX,
              ec.event.endY,
              ec.event.endX,
              ec.event.arrivalTime - ec.event.departureTime,
              ec.route.durationIntervalInSeconds,
              ec.route.durationInTrafficSeconds,
              geoUtils.distLatLon2Meters(
                new Coord(ec.event.startX, ec.event.startY),
                new Coord(ec.event.endX, ec.event.endY)
              ),
              ec.event.legLength,
              ec.route.distanceInMeters,
          )
        )
        .foreach { line =>
          csvWriter.writeRow(line)
        }
    }
    seq.size
  }

  private def getAppropriateEvents(events: Seq[PathTraversalEvent], numEventsPerHour: Int): Seq[PathTraversalEvent] = {
    val chosenEvents = Random.shuffle(events).take(numEventsPerHour)
    // Use the same events, but with departure time on 3am
    val offPeakEvents = if (cfg.offPeakEnabled) {
      chosenEvents.map(pte => pte.copy(departureTime = TimeUnit.HOURS.toSeconds(3).toInt))
    } else {
      Seq.empty
    }
    chosenEvents ++ offPeakEvents
  }

  private def getQueryDate(dateStr: String): LocalDateTime = {
    val triedDate = Try(LocalDate.parse(dateStr))
    triedDate.failed.foreach { throwable =>
      logger.error("Cannot parse date {}, using a future one", dateStr, throwable)
    }
    val parsedDate = triedDate.getOrElse(futureWednesday())
    val date = if (parsedDate.compareTo(LocalDate.now()) <= 0) {
      logger.warn("Date in the past: {}, using a future one", dateStr)
      futureWednesday()
    } else {
      parsedDate
    }
    LocalDateTime.of(date, LocalTime.MIDNIGHT)
  }

  private def futureWednesday(): LocalDate = {
    @scala.annotation.tailrec
    def findWednesday(date: LocalDate): LocalDate = {
      if (date.getDayOfWeek == DayOfWeek.WEDNESDAY) date else findWednesday(date.plusDays(1))
    }

    findWednesday(LocalDate.now().plusDays(1))
  }

  private def toRouteRequest(event: PathTraversalEvent): FindRouteRequest[PathTraversalEvent] = {
    val origin = WgsCoordinate(event.startY, event.startX)
    val destination = WgsCoordinate(event.endY, event.endX)
    FindRouteRequest(
      userObject = event,
      requestId = makeFindRouteRequestId(origin, destination, event.departureTime, constraints),
      origin = origin,
      destination = destination,
      departureAt = queryDate.plusSeconds(event.departureTime),
      constraints = constraints
    )
  }

  private def makeFindRouteRequestId(
    origin: WgsCoordinate,
    destination: WgsCoordinate,
    departureTime: Int,
    constraints: Set[TravelConstraint]
  ): String = {
    val bytes: Array[Byte] =
      s"$origin$destination$departureTime${constraints.mkString("")}"
        .getBytes("UTF-8")
    UUID.nameUUIDFromBytes(bytes).toString
  }

  def loadedEventNumber: Int = acc.size

  override def reset(iteration: Int): Unit = {
    acc.clear()
  }
}

case class EventContainer(event: PathTraversalEvent, requestId: String, route: Route)
