package beam.agentsim.events.handling

import java.nio.file.Paths
import java.time.{DayOfWeek, LocalDate, LocalDateTime, LocalTime}
import java.util.Objects
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.using
import beam.utils.csv.CsvWriter
import beam.utils.mapsapi.googleapi.GoogleAdapter.RouteRequest
import beam.utils.mapsapi.googleapi.TravelConstraints.{AvoidTolls, TravelConstraint}
import beam.utils.mapsapi.googleapi.{GoogleAdapter, Route}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable
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
      val byHour = acc.groupBy(_.departureTime % 3600).filter {
        case (hour, _) => hour < 24
      }
      val events = byHour
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

  private def queryGoogleAPI(events: Iterable[PathTraversalEvent], adapter: GoogleAdapter): List[EventContainer] = {
    val futureResult = for {
      result <- adapter.findRoutes(events.map(e => toRouteRequest(e)))
      list = result.toList
      _ = list.collect {
        case (Left(throwable), uo) => logger.error(s"Error when calling google API for $uo", throwable)
      }
    } yield
      list.collect {
        case (Right(routes), event) => routes.map(EventContainer(event, _))
      }.flatten
    Await.result(futureResult, 10.minutes)
  }

  private def writeToCsv(seq: List[EventContainer], filePath: String): Int = {
    import scala.language.implicitConversions
    val formatter = new java.text.DecimalFormat("#.########")
    implicit def doubleToString(x: Double): String = formatter.format(x)
    implicit def intToString(x: Int): String = x.toString

    val headers = Vector(
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

  private def toRouteRequest(event: PathTraversalEvent): RouteRequest[PathTraversalEvent] = {
    RouteRequest(
      userObject = event,
      origin = WgsCoordinate(event.startY, event.startX),
      destination = WgsCoordinate(event.endY, event.endX),
      departureAt = queryDate.plusSeconds(event.departureTime),
      constraints = constraints
    )
  }

  def loadedEventNumber: Int = acc.size

  override def reset(iteration: Int): Unit = {
    acc.clear()
  }
}

case class EventContainer(event: PathTraversalEvent, route: Route)
