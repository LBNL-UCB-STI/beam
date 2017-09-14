package beam.router.r5

import java.time.ZonedDateTime
import java.util
import java.util.Collections

import beam.router.RoutingModel.{BeamPath, EmptyBeamPath, TransitStopsInfo, WindowTime}
import beam.router.{StreetSegmentTrajectoryResolver, TrajectoryByEdgeIdsResolver}
import beam.sim.BeamServices
import com.conveyal.gtfs.model
import com.conveyal.gtfs.model.Stop
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.streets.{Split, StreetLayer}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object BeamPathBuilder {
  private val log  =LoggerFactory.getLogger(classOf[BeamPathBuilder])

}

class BeamPathBuilder(transportNetwork: TransportNetwork, beamServices: BeamServices) {


  import BeamPathBuilder._

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

  def buildTransitPath(transitSegment: TransitSegment, transitTripStartTime: Long, duration: Int): BeamPath = {
    if (transitSegment.middle != null) {
      val linkIds = transitSegment.middle.streetEdges.asScala.map(_.edgeId.toString).toVector
      BeamPath(linkIds, Option(TransitStopsInfo(transitSegment.from.stopId, transitSegment.to.stopId)),
        new StreetSegmentTrajectoryResolver(transitSegment.middle, transitTripStartTime))
    } else {
      val fromStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.from.stopId)
      val toStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.to.stopId)
      buildTransitPath(fromStopIntId, toStopIntId, transitTripStartTime: Long, duration)
    }
  }
  def buildTransitPath(fromStopIdx: Int, toStopIdx: Int, transitTripStartTime: Long, duration: Int): BeamPath = {
    routeTransitPathThroughStreets(transitTripStartTime, fromStopIdx, toStopIdx, TransitStopsInfo(fromStopIdx.toString, toStopIdx.toString),duration)
  }

  /**
    * Does point2point routing request to resolve appropriated route between stops
    * @param departure departure from start stop
    * @param fromStopIdx from stop
    * @param toStopIdx to stop
    * @param transitStopsInfo stop details
    * @return
    */
  def routeTransitPathThroughStreets(departure: Long, fromStopIdx: Int, toStopIdx: Int, transitStopsInfo: TransitStopsInfo, duration: Int) = {

    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
//    val fromStop = transportNetwork.transitLayer.stopForIndex.get(fromStopIdx)
//    val toStop = transportNetwork.transitLayer.stopForIndex.get(toStopIdx)
//    var fromPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,new Coord(fromStop.stop_lon,fromStop.stop_lat),100E3,StreetMode.WALK)
//    var toPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,new Coord(toStop.stop_lon,toStop.stop_lat),100E3,StreetMode.WALK)

      val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
      val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
      var fromPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,new Coord(fromVertex.getLon,fromVertex.getLat),100E3,StreetMode.WALK)
      var toPosTransformed = beamServices.geo.snapToR5Edge(transportNetwork.streetLayer,new Coord(toVertex.getLon,toVertex.getLat),100E3,StreetMode.WALK)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    //    profileRequest.maxCarTime = 6*3600
    //    profileRequest.wheelchair = false
    //    profileRequest.bikeTrafficStress = 4
    val time = WindowTime(departure.toInt, beamServices.beamConfig.beam.routing.r5.departureWindow)
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null
    val profileResponse = pointToPointQuery.getPlan(profileRequest)
    val closestDepartItinerary = profileResponse.options.asScala.headOption
    val legsBetweenStops = closestDepartItinerary match {
      case Some(option) =>
        val streetSeg =  option.access.get(0)
        val itinerary = option.itinerary.get(0)
        val tripStartTime = beamServices.dates.toBaseMidnightSeconds(itinerary.startTime, transportNetwork.transitLayer.routes.size() == 0)
        var activeLinkIds = Vector[String]()
        for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
          activeLinkIds = activeLinkIds :+ edge.edgeId.toString
        }
        BeamPath(activeLinkIds, Option(transitStopsInfo), new StreetSegmentTrajectoryResolver(streetSeg, tripStartTime))
      case None =>
        val fromEdge = transportNetwork.streetLayer.edgeStore.getCursor(transportNetwork.streetLayer.outgoingEdges.get(fromVertex.index).get(0))
        val toEdge = transportNetwork.streetLayer.edgeStore.getCursor(transportNetwork.streetLayer.outgoingEdges.get(toVertex.index).get(0))
        BeamPath(Vector(fromEdge.getEdgeIndex.toString,toEdge.getEdgeIndex.toString),Some(TransitStopsInfo(fromStopIdx.toString,toStopIdx.toString)),new TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer,departure.toLong, duration))
    }
    legsBetweenStops
  }

  def resolveFirstLastTransitEdges(stopIdxs: Int*) = {
    val edgeIds: Vector[String] = stopIdxs.map { stopIdx =>
      if(transportNetwork.transitLayer.streetVertexForStop.get(stopIdx) >= 0){
        val stopVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(stopIdx))
        val split = transportNetwork.streetLayer.findSplit(stopVertex.getLat, stopVertex.getLon, 100, StreetMode.CAR)
        if(split!=null){
          split.edge.toString
        }else{
          log.warn(s"Stop ${stopIdx} not linked to street network.")
          ""
        }
      }else{
        log.warn(s"Stop ${stopIdx} not linked to street network.")
        ""
      }
    }.toVector.distinct
    edgeIds
  }
}
