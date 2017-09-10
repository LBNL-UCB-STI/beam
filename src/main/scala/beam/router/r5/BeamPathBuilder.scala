package beam.router.r5

import java.time.ZonedDateTime
import java.util
import java.util.Collections

import beam.router.RoutingModel.{BeamPath, EmptyBeamPath, TransitStopsInfo, WindowTime}
import beam.router.StreetSegmentTrajectoryResolver
import beam.sim.BeamServices
import com.conveyal.gtfs.model
import com.conveyal.gtfs.model.Stop
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object BeamPathBuilder {
  private val log  =LoggerFactory.getLogger(classOf[BeamPathBuilder])

}

class BeamPathBuilder(transportNetwork: TransportNetwork, stopForIndex: util.List[Stop], beamServices: BeamServices) {

  import BeamPathBuilder._

  private val routingBaseDate = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate)

  def buildStreetPath(segment: StreetSegment, tripStartTime: Long): BeamPath = {
    var activeLinkIds = Vector[String]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
    }
    BeamPath(activeLinkIds, None, new StreetSegmentTrajectoryResolver(segment, tripStartTime))
  }

  def buildTransitPath(segment: StreetSegment, tripStartTime: Long, fromStopId: String, toStopId: String): BeamPath = {

    var activeLinkIds = Vector[String]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
    }
    BeamPath(activeLinkIds, Option(TransitStopsInfo(fromStopId, toStopId)),
      new StreetSegmentTrajectoryResolver(segment, tripStartTime))
  }

  def buildTransitPath(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID, transitTripStartTime: Long): BeamPath = {
    if (transitSegment.middle != null) {
      val linkIds = transitSegment.middle.streetEdges.asScala.map(_.edgeId.toString).toVector
      BeamPath(linkIds, Option(TransitStopsInfo(transitSegment.from.stopId, transitSegment.to.stopId)),
        new StreetSegmentTrajectoryResolver(transitSegment.middle, transitTripStartTime))
    } else {
      val fromStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.from.stopId)
      val toStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.to.stopId)
      val fromStop = this.stopForIndex.get(fromStopIntId)
      val toStop = this.stopForIndex.get(toStopIntId)
      val beamPath = queryTransitPath(transitTripStartTime, fromStop, toStop,
        TransitStopsInfo(fromStopIntId.toString, toStopIntId.toString))
      beamPath
    }
  }

  /**
    * Does point2point routing request to resolve appropriated route between stops
    * @param departure departure from start stop
    * @param start from stop
    * @param end to stop
    * @param transitStopsInfo stop details
    * @return
    */
  def queryTransitPath(departure: Long, start: model.Stop, end: model.Stop, transitStopsInfo: TransitStopsInfo) = {

    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromPosTransformed = beamServices.geo.utm2Wgs(new Coord(start.stop_lon, start.stop_lat))
    val toPosTransformed = beamServices.geo.utm2Wgs(new Coord(end.stop_lon, end.stop_lat))
    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    //    profileRequest.maxCarTime = 6*3600
    //    profileRequest.wheelchair = false
    //    profileRequest.bikeTrafficStress = 4
    val time = WindowTime(departure.toInt, beamServices.beamConfig.beam.routing.r5)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null
    val profileResponse = pointToPointQuery.getPlan(profileRequest)
    val closestDepartItinerary = profileResponse.options.asScala.headOption
    val legsBetweenStops = closestDepartItinerary.map { option =>
      val streetSeg =  option.access.get(0)
      val itinerary = option.itinerary.get(0)
      val tripStartTime = R5RoutingWorker.toBaseMidnightSeconds(itinerary.startTime, routingBaseDate)
      var activeLinkIds = Vector[String]()
      for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
        activeLinkIds = activeLinkIds :+ edge.edgeId.toString
      }
      BeamPath(activeLinkIds, Option(transitStopsInfo), new StreetSegmentTrajectoryResolver(streetSeg, tripStartTime))
    }

    legsBetweenStops.getOrElse{
      log.warn("Couldn't find legs between stops: {} ( {} ), {} ( {} )", start.stop_name, start.stop_id, end.stop_name, end.stop_id)
      EmptyBeamPath.path
    }
  }

  def resolveTransitEdges(stops: model.Stop*) = {
    val edgeIds: Vector[String] = stops.flatMap { stop =>
      val split = transportNetwork.streetLayer.findSplit(stop.stop_lat, stop.stop_lon, 25, StreetMode.CAR)
      Option(split).map(_.edge.toString)
    }.toVector.distinct
    edgeIds
  }
}
