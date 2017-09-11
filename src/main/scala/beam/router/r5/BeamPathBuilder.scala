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

  def buildTransitPath(transitSegment: TransitSegment, transitTripStartTime: Long): BeamPath = {
    if (transitSegment.middle != null) {
      val linkIds = transitSegment.middle.streetEdges.asScala.map(_.edgeId.toString).toVector
      BeamPath(linkIds, Option(TransitStopsInfo(transitSegment.from.stopId, transitSegment.to.stopId)),
        new StreetSegmentTrajectoryResolver(transitSegment.middle, transitTripStartTime))
    } else {
      val fromStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.from.stopId)
      val toStopIntId = this.transportNetwork.transitLayer.indexForStopId.get(transitSegment.to.stopId)
      buildTransitPath(fromStopIntId, toStopIntId, transitTripStartTime: Long)
    }
  }
  def buildTransitPath(fromStopIdx: Int, toStopIdx: Int, transitTripStartTime: Long): BeamPath = {
    routeTransitPathThroughStreets(transitTripStartTime, fromStopIdx, toStopIdx, TransitStopsInfo(fromStopIdx.toString, toStopIdx.toString))
  }

  /**
    * Does point2point routing request to resolve appropriated route between stops
    * @param departure departure from start stop
    * @param fromStopIdx from stop
    * @param toStopIdx to stop
    * @param transitStopsInfo stop details
    * @return
    */
  def routeTransitPathThroughStreets(departure: Long, fromStopIdx: Int, toStopIdx: Int, transitStopsInfo: TransitStopsInfo) = {

    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone
    val fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(fromStopIdx))
    val toVertex = transportNetwork.streetLayer.vertexStore.getCursor(transportNetwork.transitLayer.streetVertexForStop.get(toStopIdx))
    val fromPosTransformed = beamServices.geo.utm2Wgs(new Coord(fromVertex.getLon, fromVertex.getLat))
    val toPosTransformed = beamServices.geo.utm2Wgs(new Coord(toVertex.getLon, toVertex.getLat))
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
    profileRequest.date = beamServices.dates.localBaseDate
    profileRequest.directModes = util.EnumSet.copyOf(Collections.singleton(LegMode.CAR))
    profileRequest.transitModes = null
    profileRequest.accessModes = profileRequest.directModes
    profileRequest.egressModes = null
    val profileResponse = pointToPointQuery.getPlan(profileRequest)
    val closestDepartItinerary = profileResponse.options.asScala.headOption
    val legsBetweenStops = closestDepartItinerary.map { option =>
      val streetSeg =  option.access.get(0)
      val itinerary = option.itinerary.get(0)
      val tripStartTime = beamServices.dates.toBaseMidnightSeconds(itinerary.startTime, transportNetwork.transitLayer.routes.size() == 0)
      var activeLinkIds = Vector[String]()
      for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
        activeLinkIds = activeLinkIds :+ edge.edgeId.toString
      }
      BeamPath(activeLinkIds, Option(transitStopsInfo), new StreetSegmentTrajectoryResolver(streetSeg, tripStartTime))
    }

    legsBetweenStops.getOrElse{
      log.warn(s"Couldn't find legs between stops: ${fromVertex}, ${toVertex} ")
      EmptyBeamPath.path
    }
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
