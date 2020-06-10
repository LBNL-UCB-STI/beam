package beam.agentsim.events.handling

import java.time.{LocalDate, LocalDateTime, LocalTime}
import java.util.Objects

import akka.actor.ActorSystem
import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.FileUtils.using
import beam.utils.mapsapi.googleapi.{GoogleAdapter, Route, TrafficModels, TravelModes}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.utils.io.IOUtils

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Random, Success, Try}

/**
  *
  * @author Dmitry Openkov
  */
class TravelTimeGoogleStatistic(cfg: BeamConfig.Beam.Calibration.Google, actorSystem: ActorSystem, geoUtils: GeoUtils)
    extends BasicEventHandler
    with IterationEndsListener
    with LazyLogging {
  if (cfg.travelTimes.enable && cfg.apiKey.isEmpty)
    throw new IllegalArgumentException("google api key is empty")

  val acc = mutable.ListBuffer.empty[PathTraversalEvent]

  override def handleEvent(event: Event): Unit = {
    if (cfg.travelTimes.enable) {
      event match {
        case pte: PathTraversalEvent => acc += pte
        case _                       =>
      }
    }
  }

  private def lift[T](futures: Iterable[Future[T]]): Iterable[Future[Try[T]]] =
    futures.map(_.map {
      Success(_)
    }.recover {
      case throwable => Failure(throwable)
    })

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    @scala.annotation.tailrec
    def queryGoogleAPI(
      events: List[Iterable[PathTraversalEvent]],
      adapter: GoogleAdapter,
      acc: List[EventContainer]
    ): List[EventContainer] = {
      events match {
        case Nil => acc
        case head :: tl =>
          val futureResult = for {
            result <- Future.sequence(lift(head.map(e => toGoogleTravelTime(e, adapter))))
            _ = result.collect {
              case Failure(throwable) => logger.error("Error when calling google API", throwable)
            }
            flat = result.collect {
              case Success(value) => value
            }.flatten
          } yield flat
          val eventContainers = Await.result(futureResult, 1.hour)
          queryGoogleAPI(tl, adapter, acc ++ eventContainers)
      }
    }

    if (cfg.travelTimes.enable
        && cfg.travelTimes.iterationInterval > 0
        && event.getIteration % cfg.travelTimes.iterationInterval == 0) {
      logger.info("Executing google API call for iteration #{}", event.getIteration)
      val numEventsPerHour = Math.max(1, cfg.travelTimes.numDataPointsOver24Hours / 24)
      val byHour = acc.groupBy(_.departureTime % 3600).filter {
        case (hour, _) => hour < 24
      }
      val events = byHour
        .flatMap {
          case (_, events) => getAppropriateEvents(events, numEventsPerHour, cfg.travelTimes.minDistanceInMeters)
        }
      val groupedEvents = events.grouped(16).toList
      logger.info("Number of events: {}", events.size)
      val apiKey = cfg.apiKey.getOrElse(throw new IllegalArgumentException("No google api key provided"))
      val adapter = new GoogleAdapter(apiKey, None, Some(actorSystem))
      val result = using(adapter) { adapter =>
        queryGoogleAPI(groupedEvents, adapter, List())
      }.sortBy(
        ec => (ec.event.departureTime, ec.event.vehicleId, ec.route.durationIntervalInSeconds)
      )
      val iterationNumber = event.getServices.getIterationNumber
      val iterationPath = event.getServices.getControlerIO.getIterationPath(iterationNumber)
      val filePath = s"$iterationPath/$iterationNumber.googleTravelTimeEstimation.csv"
      val num = writeToCsv(result, filePath)
      logger.info(s"Saved $num routes to $filePath")
    }
  }

  private def writeToCsv(seq: List[EventContainer], filePath: String): Int = {
    import scala.language.implicitConversions
    val formatter = new java.text.DecimalFormat("#.########")
    implicit def doubleToString(x: Double): String = formatter.format(x)
    implicit def intToString(x: Int): String = x.toString

    using(IOUtils.getBufferedWriter(filePath)) { writer =>
      writer.write(
        "vehicleId,vehicleType,departureTime,originLat,originLng,destLat,destLng,simTravelTime,googleTravelTime," +
        "euclideanDistanceInMeters,legLength,googleDistance"
      )
      seq
        .map(
          ec =>
            List[String](
              Objects.toString(ec.event.vehicleId),
              ec.event.vehicleType,
              ec.event.departureTime,
              ec.event.startY,
              ec.event.startX,
              ec.event.endY,
              ec.event.endX,
              ec.event.arrivalTime - ec.event.departureTime,
              ec.route.durationIntervalInSeconds,
              geoUtils.distLatLon2Meters(
                new Coord(ec.event.startX, ec.event.startY),
                new Coord(ec.event.endX, ec.event.endY)
              ),
              ec.event.legLength,
              ec.route.distanceInMeters,
            ).mkString(",")
        )
        .foreach { line =>
          writer.newLine()
          writer.write(line)
        }
    }
    seq.size
  }

  private def getAppropriateEvents(
    events: Seq[PathTraversalEvent],
    numEventsPerHour: Int,
    minDistanceInMeters: Double
  ) = {
    Random.shuffle(events).view.filter(e => e.legLength >= minDistanceInMeters).take(numEventsPerHour)
  }

  def toGoogleTravelTime(event: PathTraversalEvent, adapter: GoogleAdapter): Future[Seq[EventContainer]] = {
    for {
      routes <- adapter
        .findRoutes(
          origin = WgsCoordinate(event.startY, event.startX),
          destination = WgsCoordinate(event.endY, event.endX),
          departureAt = toLocalDateTime(event.departureTime),
          mode = TravelModes.Driving,
          trafficModel = TrafficModels.BestGuess,
          constraints = Set.empty
        )
    } yield routes.map(route => EventContainer(event, route))
  }

  def toLocalDateTime(departureTime: Int): LocalDateTime = {
    val today = LocalDate.now()
    val todayMidnight = LocalDateTime.of(today, LocalTime.MIDNIGHT)
    val futureMidnight = todayMidnight.plusDays(2)
    futureMidnight.plusSeconds(departureTime)
  }

  override def reset(iteration: Int): Unit = {
    acc.clear()
  }
}

case class EventContainer(event: PathTraversalEvent, route: Route)
