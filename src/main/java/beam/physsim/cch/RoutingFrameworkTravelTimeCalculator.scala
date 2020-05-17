package beam.physsim.cch

import java.util
import java.util.concurrent.Executors

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamServices
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Coordinate
import org.apache.commons.lang.time.StopWatch
import org.matsim.api.core.v01.network.Link
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class RoutingFrameworkTravelTimeCalculator(
  private val beamServices: BeamServices,
  private val osmInfoHolder: OsmInfoHolder,
  private val routingFrameworkWrapper: RoutingFrameworkWrapper,
) extends LazyLogging {

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(
      Math.max(Runtime.getRuntime.availableProcessors() / 4, 1),
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("routing-framework-worker-%d").build()
    )
  )

  private val odsFactor: Int =
    Math.max(
      (1.0 / beamServices.beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation *
      beamServices.beamConfig.beam.physsim.cch.congestionFactor).toInt,
      1
    )

  private val graph: RoutingFrameworkGraph = routingFrameworkWrapper.generateGraph()

  private val coord2VertexId: Map[Coordinate, Long] =
    graph.vertices.map(x => x.coordinate -> x.id).toMap

  private val coordKey = (coord: Coordinate) => (coord.x * 10).toInt + "|" + (coord.y * 10).toInt

  private val coordKey2Coordinates: Map[String, Seq[Vertex]] = graph.vertices
    .map(v => coordKey(v.coordinate) -> v)
    .groupBy(_._1)
    .mapValues(_.map(_._2))

  def getLink2TravelTimes(
    pathTraversalEvents: util.Collection[PathTraversalEvent],
    iterationEndsEvent: IterationEndsEvent,
    links: util.Collection[_ <: Link],
    maxHour: Int
  ): java.util.Map[String, Array[Double]] = {
    val iterationNumber: Int = iterationEndsEvent.getIteration
    val startTime: Long = System.currentTimeMillis

    logger.info("Finished creation of graph {}", System.currentTimeMillis - startTime)

    val id2Link = links.toStream.map(x => x.getId.toString.toInt -> x).toMap

    val hour2Events: Map[Int, List[TravelInfo]] = generateHour2Events(pathTraversalEvents)

    val odsCreationFutures = hour2Events
      .map {
        case (hour, events) =>
          Future {
            val stopWatch: StopWatch = new StopWatch
            stopWatch.start()

            var odNumber = 0

            val ods = events.toStream
              .map { event =>
                for {
                  firstLinkId <- event.linkIds.headOption
                  lastLinkId  <- event.linkIds.lastOption
                  firstWayId  <- linkWayId(id2Link(firstLinkId))
                  secondWayId <- linkWayId(id2Link(lastLinkId))
                  origin      <- osmInfoHolder.getCoordinatesForWayId(firstWayId).headOption
                  destination <- osmInfoHolder.getCoordinatesForWayId(secondWayId).lastOption
                } yield origin -> destination
              }
              .collect { case Some(od @ (origin, destination)) if origin != destination => od }
              .map {
                case (origin, destination) =>
                  val firstVertexId: Long = coord2VertexId
                    .getOrElse(origin, getClosestVertexId(origin))
                  val secondVertexId: Long = coord2VertexId
                    .getOrElse(destination, getClosestVertexId(destination))

                  odNumber = odNumber + 1

                  OD(firstVertexId, secondVertexId)
              }

            val odStream = ods
              .flatMap(od => Stream.range(0, odsFactor, 1).map(_ => od))
            routingFrameworkWrapper.generateOd(iterationNumber, hour, odStream)

            logger.info("Generated {} ods, for hour {} in {} ms", odNumber, hour, stopWatch.getTime)
          }
      }

    Await.result(Future.sequence(odsCreationFutures), 10.minutes)

    val hour2Way2TravelTimes = hour2Events.keys.toList.sorted.map { hour =>
      val stopWatch: StopWatch = new StopWatch
      stopWatch.start()

      logger.info("Starting traffic assignment for hour {}", hour)
      val way2TravelTime =
        routingFrameworkWrapper.assignTrafficAndFetchWay2TravelTime(iterationNumber, hour)
      logger.info("Traffic assignment for hour {} is finished in {} ms", hour, stopWatch.getTime)

      (hour, way2TravelTime)
    }.toMap

    val totalNumberOfLinks: Int = links.size
    var linksFailedToResolve = 0

    val linkId2TravelTimeByHour = links
      .groupBy(linkWayId)
      .flatMap {
        case (maybeWayId, linksInWay) =>
          linksInWay.map { link =>
            val travelTimeByHour = maybeWayId match {
              case Some(wayId) =>
                var speedFoundForAtLeastOneHour = false

                val linkTravelTimeByHour = Array.tabulate(maxHour) { hour =>
                  hour2Way2TravelTimes.get(hour).flatMap(_.get(wayId)) match {
                    case Some(travelTime) =>
                      speedFoundForAtLeastOneHour = true
                      travelTime / linksInWay.size

                    case None =>
                      link.getLength / link.getFreespeed
                  }
                }

                if (!speedFoundForAtLeastOneHour)
                  linksFailedToResolve = linksFailedToResolve + 1

                linkTravelTimeByHour

              case None =>
                linksFailedToResolve = linksFailedToResolve + 1
                Array.fill(maxHour) { link.getLength / link.getFreespeed }
            }
            link.getId.toString -> travelTimeByHour
          }
      }

    logger.info("Total links: {}, failed to assign travel time: {}", totalNumberOfLinks, linksFailedToResolve)

    logger.info("Created travel times in {} ms", System.currentTimeMillis - startTime)

    linkId2TravelTimeByHour.asJava
  }

  private def generateHour2Events(
    pathTraversalEvents: util.Collection[PathTraversalEvent]
  ): Map[Int, List[TravelInfo]] = {
    val preliminaryHour2Events: Map[Int, List[PathTraversalEvent]] =
      pathTraversalEvents.toStream
        .map(x => x.departureTime / 3600 -> x)
        .groupBy(_._1)
        .mapValues(_.map(_._2).toList)

    val hours2EventsMutable = mutable.HashMap[Int, mutable.ArrayBuffer[TravelInfo]]()

    preliminaryHour2Events.foreach {
      case (hour, events) =>
        events.foreach { event =>
          val currentLinkIds = mutable.ArrayBuffer[Int]()
          var currentHour = hour

          var currentTime = event.departureTime.toDouble
          event.linkTravelTime.zip(event.linkIds).foreach {
            case (travelTime, linkId) =>
              currentLinkIds += linkId
              currentTime = currentTime + travelTime
              val possiblyNextHour = currentTime.toInt / 3600

              if (possiblyNextHour != currentHour) {
                val currentHourEvents =
                  hours2EventsMutable.getOrElseUpdate(currentHour, new mutable.ArrayBuffer[TravelInfo]())
                currentHourEvents += TravelInfo(currentLinkIds.toIndexedSeq)
                currentLinkIds.clear()

                currentHour = possiblyNextHour
              }
          }
          if (currentLinkIds.nonEmpty) {
            val currentHourEvents =
              hours2EventsMutable.getOrElseUpdate(currentHour, new mutable.ArrayBuffer[TravelInfo]())
            currentHourEvents += TravelInfo(currentLinkIds.toIndexedSeq)
          }
        }
    }

    hours2EventsMutable.mapValues(_.toList).toMap
  }

  private def linkWayId(link: Link): Option[Long] =
    Option(link.getAttributes.getAttribute("origid"))
      .map(_.toString.toLong)

  private def getClosestVertexId(
    coordinate: Coordinate
  ): Long = {
    val vertexes =
      coordKey2Coordinates.getOrElse(coordKey(coordinate), Nil)

    if (vertexes.nonEmpty) {
      vertexes.minBy(x => x.coordinate.distance(coordinate)).id
    } else {
      coord2VertexId.minBy(x => x._1.distance(coordinate))._2
    }
  }
}

case class TravelInfo(linkIds: IndexedSeq[Int])

case class OD(oriding: Long, destionation: Long)
