package beam.agentsim.events.handling

import java.nio.file.Paths
import java.time._
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.handling.TravelTimeGoogleStatistic._
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.using
import beam.utils.google_routes_db.GoogleRoutesDB
import beam.utils.mapsapi.googleapi.{GoogleAdapter, GoogleRoutesResponse}
import beam.utils.mapsapi.googleapi.GoogleAdapter.{FindRouteRequest, FindRouteResult}
import beam.utils.mapsapi.googleapi.TravelConstraints.{AvoidTolls, TravelConstraint}
import com.google.maps.model.DirectionsResult
import com.typesafe.scalalogging.LazyLogging
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource
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
  * TravelTimeGoogleStatistic listens [[PathTraversalEvent]]'s of [[BeamMode.CAR]] mode and
  * outputs googleTravelTimeEstimation.csv in iteration folder.
  * It uses a prebuilt database as cache (see [[beam.utils.google_routes_db.build.BuildGoogleRoutesDBApp]] and
  * Google Maps (Directions) API for missing routes (see [[GoogleAdapter]]).
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

  private val maybeDataSource: Option[DataSource] =
    Option(cfg.enable && cfg.googleRoutesDb.enable)
      .filter(x => x)
      .flatMap(_ => cfg.googleRoutesDb.postgresql)
      .map(makeDataSource)

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
        "Querying Google DirectionAPI routes for iteration #{}, query date = {}",
        event.getIteration,
        queryDate.toLocalDate
      )
      val numEventsPerHour = Math.max(1, cfg.numDataPointsOver24Hours / 24)
      val byHour: Map[Int, ListBuffer[PathTraversalEvent]] = acc
        .groupBy(pte => departureHour(pte.departureTime))
        .filter {
          case (hour, _) => hour < 24
        }
      val events: immutable.Iterable[PathTraversalEvent] = byHour
        .flatMap {
          case (_, events) => getAppropriateEvents(events, numEventsPerHour)
        }
      logger.info("Number of events: {}", events.size)

      val entriesFromCache: Map[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]] =
        queryGoogleRoutesFromDb(events, queryDate, geoUtils).filter {
          case (_, seq) => seq.nonEmpty
        }

      val missingEvents = events.filterNot(entriesFromCache.keySet.contains)

      val controller = event.getServices.getControlerIO
      val filePath = controller
        .getIterationFilename(event.getIteration, "googleTravelTimeEstimation.csv")

      val entriesFromGoogle: Map[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]] =
        if (missingEvents.isEmpty) {
          logger.info(
            "Got {} Google routes from cache",
            entriesFromCache.values.map(_.size).sum,
          )
          Map.empty
        } else {

          logger.info(
            "Got {} Google routes from cache, calling Google DirectionsApi for {} routes",
            entriesFromCache.keySet.size,
            missingEvents.size
          )

          val adapter = new GoogleAdapter(
            apiKey,
            Some(
              Paths.get(
                controller.getIterationFilename(
                  event.getIteration,
                  "maps.googleapi.responses.json"
                )
              )
            ),
            Some(actorSystem)
          )
          val eventContainers = using(adapter) { adapter =>
            queryGoogleAPI(missingEvents, adapter)
          }

          val googleEntries = makeGTTEEntries(eventContainers)

          val requestIdToDepartureTime: Map[String, Int] =
            googleEntries.values.flatten.groupBy(_.requestId).mapValues(_.head.departureTime)

          try {
            insertGoogleRoutesAndLegsInDb(
              eventContainers.map { ec =>
                GoogleRoutesResponse(
                  requestId = ec.requestId,
                  departureLocalDateTime = makeDepartureLocatDateTime(ec.event.departureTime)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                  ec.directionsResult
                )
              },
              requestIdToDepartureTime,
              filePath,
              Instant.now()
            )
          } catch {
            case e: Throwable =>
              logger.warn("Failed to insert Google routes", e)
          }

          googleEntries
        }

      val allEntries = (entriesFromCache.values.flatten ++ entriesFromGoogle.values.flatten).toSeq
        .sortBy { gttee =>
          (gttee.departureTime, gttee.vehicleId, gttee.googleTravelTimeWithTraffic)
        }

      GoogleTravelTimeEstimationEntry.Csv.writeEntries(allEntries, filePath)
      logger.info(s"Saved ${allEntries.size} routes to $filePath")
    }
  }

  private def queryGoogleRoutesFromDb(
    ptes: TraversableOnce[PathTraversalEvent],
    departureDateTime: LocalDateTime,
    geoUtils: GeoUtils
  ): Map[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]] = {
    maybeDataSource
      .map { ds =>
        try {
          using(ds.getConnection) { con =>
            GoogleRoutesDB.queryGoogleRoutes(ptes, departureDateTime, geoUtils, con)
          }
        } catch {
          case e: Throwable =>
            logger.warn("Failed to query GoogleRoutesDB", e)
            Map.empty[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]]
        }
      }
      .getOrElse(Map.empty)
  }

  def insertGoogleRoutesAndLegsInDb(
    grrSeq: Seq[GoogleRoutesResponse],
    requestIdToDepartureTime: Map[String, Int],
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant
  ): Unit = {
    maybeDataSource.foreach { ds =>
      try {
        using(ds.getConnection) { con =>
          GoogleRoutesDB.insertGoogleRoutesAndLegs(
            grrSeq,
            requestIdToDepartureTime,
            googleapiResponsesJsonFileUri,
            timestamp,
            con
          )
        }
      } catch {
        case e: Throwable =>
          logger.warn("Failed to insert GoogleRoutes", e)
      }
    }
  }

  private def queryGoogleAPI(events: Iterable[PathTraversalEvent], adapter: GoogleAdapter): Seq[EventContainer] = {
    val containersFuture = adapter
      .findRoutes(events.map(toRouteRequest))
      .map(_.flatMap { result =>
        val FindRouteResult(event, requestId, eitherRes) = result

        eitherRes match {
          case Left(e) =>
            logger.error(s"Error when calling google API for $event", e)
            Seq.empty[EventContainer]

          case Right(res) =>
            Seq(EventContainer(event, requestId, res))
        }
      })

    Await.result(containersFuture, 10.minutes)
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
      requestId = UUID.randomUUID().toString,
      origin = origin,
      destination = destination,
      departureAt = makeDepartureLocatDateTime(event.departureTime),
      constraints = constraints
    )
  }

  private def makeDepartureLocatDateTime(departureTime: Int): LocalDateTime = {
    queryDate.plusSeconds(departureTime)
  }

  def loadedEventNumber: Int = acc.size

  override def reset(iteration: Int): Unit = {
    acc.clear()
  }

  private def makeGTTEEntries(
    ecs: TraversableOnce[EventContainer]
  ): Map[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]] = {
    val result = mutable.Map.empty[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]]

    ecs.foreach { ec =>
      val gttees = ec.directionsResult.routes.flatMap { route =>
        route.legs.map { leg =>
          GoogleTravelTimeEstimationEntry.fromPTE(
            ec.event,
            ec.requestId,
            leg.duration.inSeconds,
            Option(leg.durationInTraffic).map(_.inSeconds),
            leg.distance.inMeters,
            geoUtils
          )
        }
      }
      result += ec.event -> gttees
    }

    result.toMap
  }

  private def departureHour(fromDepartureTime: Int): Int = {
    fromDepartureTime % 3600
  }
}

object TravelTimeGoogleStatistic {

  case class EventContainer(
    event: PathTraversalEvent,
    requestId: String,
    directionsResult: DirectionsResult
  )

  def makeDataSource(
    cfg: BeamConfig.Beam.Calibration.Google.TravelTimes.GoogleRoutesDb.Postgresql
  ): DataSource = {
    val ds = new BasicDataSource()
    ds.setDriverClassName("org.postgresql.Driver")
    ds.setUrl(cfg.url)
    ds.setUsername(cfg.username)
    ds.setPassword(cfg.password)

    ds
  }
}
