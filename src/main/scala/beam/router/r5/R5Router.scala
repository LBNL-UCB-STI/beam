package beam.router.r5

import java.io.{File, FileInputStream, InputStream}
import java.nio.file.Files.{exists, isReadable}
import java.nio.file.Paths
import java.util

import akka.actor.Props
import beam.router.BeamRouter
import beam.router.BeamRouter.{HasProps, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.sim.BeamServices
import beam.utils.GeoUtils
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util.{LegMode, StreetEdgeInfo, StreetSegment, TransitModes}
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.LineString
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility

import scala.collection.JavaConverters._

class R5Router(beamServices: BeamServices) extends BeamRouter {
  private val GRAPH_FILE = "/network.dat"

  override var services: BeamServices = beamServices

  private lazy val networkDir = beamServices.beamConfig.beam.routing.r5.directory
  var transportNetwork: TransportNetwork = null

  override def init = loadMap

  def loadMap = {
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir();
    }
    val networkFilePath = Paths.get(networkDir, GRAPH_FILE)
    val networkFile : File = networkFilePath.toFile
    if (exists(networkFilePath)) {
      transportNetwork = TransportNetwork.read(networkFile)
    }else {
      transportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile)
      transportNetwork.write(networkFile);
      transportNetwork = TransportNetwork.read(networkFile);
    }
  }

  override def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], person: Person, considerTransit: Boolean = false) = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val plan = pointToPointQuery.getPlan(buildRequest(fromFacility, toFacility, departureTime, accessMode, considerTransit))
    buildResponse(plan)
  }

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, accessMode: Vector[BeamMode], isTransit: Boolean = false) : ProfileRequest = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromPosTransformed = GeoUtils.transform.Utm2Wgs(fromFacility.getCoord)
    val toPosTransformed = GeoUtils.transform.Utm2Wgs(toFacility.getCoord)

    profileRequest.fromLat = fromPosTransformed.getX
    profileRequest.fromLon = fromPosTransformed.getY
    profileRequest.toLat = toPosTransformed.getX
    profileRequest.toLon = toPosTransformed.getY
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4

    //setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00")
    val time = departureTime.asInstanceOf[WindowTime]
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime

    if(isTransit) {
      profileRequest.transitModes = util.EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS, TransitModes.SUBWAY, TransitModes.RAIL)
    }
    profileRequest.accessModes = util.EnumSet.of(LegMode.WALK)
    profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)

    profileRequest.directModes = util.EnumSet.copyOf(accessMode.map(m => LegMode.valueOf(m.value)).asJavaCollection)
//    profileRequest.directModes = util.EnumSet.of(LegMode.WALK, LegMode.BICYCLE)

    profileRequest
  }

  def buildResponse(plan: ProfileResponse): RoutingResponse = {
//    RoutingResponse((for(option: ProfileOption <- plan.options.asScala) yield
//      BeamTrip( (for((itinerary, access) <- option.itinerary.asScala zip option.access.asScala) yield
//        BeamLeg(itinerary.startTime.toEpochSecond, BeamMode.withValue(access.mode.name()), itinerary.duration, null)
//      ).toVector)
//    ).toVector)

    RoutingResponse(plan.options.asScala.map(option =>
      BeamTrip( (for((itinerary, access) <- option.itinerary.asScala zip option.access.asScala) yield
        BeamLeg(itinerary.startTime.toEpochSecond, BeamMode.withValue(access.mode.name()), itinerary.duration, buildGraphPath(access))
        ).toVector)
      ).toVector)
  }

  def buildGraphPath(segment: StreetSegment): BeamGraphPath = {
    var activeLinkIds = Vector[String]()
    //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
    var activeCoords = Vector[Coord]()
    var activeTimes = Vector[Long]()
    for(edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds = activeLinkIds :+ edge.edgeId.toString
      activeCoords = activeCoords :+ toCoord(edge.geometry)
    }
    BeamGraphPath(activeLinkIds, activeCoords, activeTimes)
  }

  def toCoord(geometry: LineString): Coord = {
    new Coord(geometry.getCoordinate.x, geometry.getCoordinate.y, geometry.getCoordinate.z)
  }

  private def buildPath(profileRequest: ProfileRequest, streetMode: StreetMode) = {

    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = streetMode

    // TODO use target pruning instead of a distance limit
    streetRouter.distanceLimitMeters = 100000

    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)

    streetRouter.route

    //Gets lowest weight state for end coordinate split
    val lastState = streetRouter.getState(streetRouter.getDestinationSplit())
    val streetPath = new StreetPath(lastState, transportNetwork)

    var stateIdx = 0
    var activeLinkIds = Vector[String]()
    //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
    var activeCoords = Vector[Coord]()
    var activeTimes = Vector[Long]()

    for (state <- streetPath.getStates.asScala) {
      val edgeIdx = state.backEdge
      if (!(edgeIdx == null || edgeIdx == -1)) {
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx)
        activeLinkIds = activeLinkIds :+ edgeIdx.toString
        activeCoords = activeCoords :+ toCoord(edge.getGeometry)
        activeTimes = activeTimes :+ state.getDurationSeconds.toLong
      }
    }
    BeamGraphPath(activeLinkIds, activeCoords, activeTimes)
  }
}

object R5Router extends HasProps {
  override def props(beamServices: BeamServices) = Props(classOf[R5Router], beamServices)
}