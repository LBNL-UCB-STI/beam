package beam.physsim.cchRoutingAssignment

import java.util
import java.util.concurrent.Executors

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamServices
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import com.vividsolutions.jts.index.kdtree.{KdNode, KdTree}
import org.apache.commons.lang.time.StopWatch
import org.matsim.api.core.v01.network.Link

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversionsToScala._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Generates traveltime for links by hours
  */
class RoutingFrameworkTravelTimeCalculator(
  private val beamServices: BeamServices,
  private val osmInfoHolder: OsmInfoHolder,
  private val routingFrameworkWrapper: RoutingFrameworkWrapper
) extends LazyLogging {

  private implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(
    Executors.newFixedThreadPool(
      Runtime.getRuntime.availableProcessors(),
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("routing-framework-worker-%d").build()
    )
  )

  private val odsFactor: Double =
    Math.max(
      (1.0 / beamServices.beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation *
      beamServices.beamConfig.beam.physsim.cchRoutingAssignment.congestionFactor).toInt,
      1.0
    )

  private val graph: RoutingFrameworkGraph = routingFrameworkWrapper.generateGraph()

  private val coord2VertexId: Map[Coordinate, Long] =
    graph.vertices.map(x => x.coordinate -> x.id).toMap

  private val kdTree = new KdTree()
  graph.vertices.foreach { x =>
    kdTree.insert(x.coordinate, x.id)
  }

  def generateLink2TravelTimes(
    pathTraversalEvents: util.Collection[PathTraversalEvent],
    iterationNumber: Int,
    links: util.Collection[_ <: Link],
    maxHour: Int
  ): java.util.Map[String, Array[Double]] = {

    val travelTimeCalculationStopWatch = new StopWatch()

    val id2Link = links.toStream.map(x => x.getId.toString.toInt -> x).toMap

    val hour2Events = generateHour2Events(pathTraversalEvents)

    val odsCreationFutures = hour2Events
      .map { case (hour, events) =>
        Future {
          val stopWatch: StopWatch = new StopWatch
          stopWatch.start()

          val ods = generateOdsFromTravelInfos(events, id2Link).toList

          val odStream = Stream.fill(odsFactor.toInt)(ods).flatten ++
            scala.util.Random.shuffle(ods).toStream.take(((odsFactor % 1) * ods.size).toInt)

          routingFrameworkWrapper.writeOds(iterationNumber, hour, odStream)

          logger.info(
            "Generated {} ods ({} with ods factor), for hour {} in {} ms",
            ods.size,
            (ods.size * odsFactor).toInt,
            hour,
            stopWatch.getTime
          )
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

    val (linksFailedToResolve, linkId2TravelTimeByHour) =
      fillLink2TravelTimeByHour(links, hour2Way2TravelTimes, maxHour)

    logger.info("Total links: {}, failed to assign travel time: {}", totalNumberOfLinks, linksFailedToResolve)

    logger.info("Created travel times in {} ms", travelTimeCalculationStopWatch.getTime)

    linkId2TravelTimeByHour.asJava
  }

  private[cchRoutingAssignment] def fillLink2TravelTimeByHour(
    links: util.Collection[_ <: Link],
    hour2Way2TravelTimes: Map[Int, Map[Long, Double]],
    maxHour: Int
  ): (Int, Map[String, Array[Double]]) = {
    var linksFailedToResolve = 0

    val linkId2TravelTimeByHour = links
      .groupBy(linkWayId)
      .flatMap { case (maybeWayId, linksInWay) =>
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
    (linksFailedToResolve, linkId2TravelTimeByHour)
  }

  private[cchRoutingAssignment] def generateOdsFromTravelInfos(
    events: Seq[TravelInfo],
    id2Link: Map[Int, Link]
  ): Stream[OD] = {
    events.toStream
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
      .map { case (origin, destination) =>
        val firstVertexId: Long = coord2VertexId
          .getOrElse(origin, getClosestVertexId(origin))
        val secondVertexId: Long = coord2VertexId
          .getOrElse(destination, getClosestVertexId(destination))

        OD(firstVertexId, secondVertexId)
      }
  }

  private[cchRoutingAssignment] def generateHour2Events(
    pathTraversalEvents: util.Collection[PathTraversalEvent]
  ): Map[Int, List[TravelInfo]] = {
    val preliminaryHour2Events: Map[Int, Iterable[PathTraversalEvent]] =
      pathTraversalEvents
        .groupBy(x => x.departureTime / 3600)

    val hours2EventsMutable = mutable.HashMap[Int, mutable.ArrayBuffer[TravelInfo]]()

    preliminaryHour2Events.foreach { case (hour, events) =>
      events.foreach { event =>
        val currentLinkIds = mutable.ArrayBuffer[Int]()
        var currentHour = hour

        var currentTime = event.departureTime.toDouble
        event.linkTravelTime.zip(event.linkIds).foreach { case (travelTime, linkId) =>
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
    var distance = 0.00001
    val envelope = new Envelope(coordinate)

    Stream
      .range(1, 10)
      .map { _ =>
        distance = distance * 10
        envelope.expandBy(distance)
        kdTree.query(envelope).asInstanceOf[java.util.ArrayList[KdNode]].toList match {
          case Nil => None
          case res =>
            Some(
              res.minBy(_.getCoordinate.distance(coordinate)).getData.asInstanceOf[Long]
            )
        }
      }
      .collectFirst { case Some(v) => v }
      .get
  }
}

case class TravelInfo(linkIds: IndexedSeq[Int])

case class OD(origin: Long, destination: Long)
