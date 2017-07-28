package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths
import java.time.{LocalDate, ZonedDateTime}
import java.time.temporal.ChronoUnit
import java.util

import akka.actor.Props
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter.RoutingResponse
import beam.router.Modes.BeamMode
import beam.router.RoutingModel._
import beam.router.RoutingWorker
import beam.router.RoutingWorker.HasProps
import beam.router.r5.R5RoutingWorker.{GRAPH_FILE, transportNetwork}
import beam.sim.BeamServices
import beam.utils.GeoUtils
import beam.utils.CollectionUtils.onContains
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util.{LegMode, StreetEdgeInfo, StreetSegment, TransitModes}
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.LineString
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility

import scala.collection.JavaConverters._

class R5RoutingWorker(beamServices: BeamServices) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
  val localDateAsString: String = "2016-10-17"
  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond

  override var services: BeamServices = beamServices

  override def init = loadMap

  def loadMap = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
    val networkDirPath = Paths.get(networkDir)
    if (!exists(networkDirPath)) {
      Paths.get(networkDir).toFile.mkdir();
    }
    val networkFilePath = Paths.get(networkDir, GRAPH_FILE)
    val networkFile : File = networkFilePath.toFile
    if (exists(networkFilePath)) {
      log.debug(s"Initializing router by reading network from: ${networkFilePath.toAbsolutePath}")
      transportNetwork = TransportNetwork.read(networkFile)
    } else {
      log.debug(s"Network file [${networkFilePath.toAbsolutePath}] not found. ")
      log.debug(s"Initializing router by creating network from: ${networkDirPath.toAbsolutePath}")
      transportNetwork = TransportNetwork.fromDirectory(networkDirPath.toFile)
      transportNetwork.write(networkFile);
//      transportNetwork = TransportNetwork.read(networkFile);
    }
  }

  override def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, modes: Vector[BeamMode], person: Person) = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val plan: ProfileResponse = pointToPointQuery.getPlan(buildRequest(fromFacility, toFacility, departureTime, modes))
    buildResponse(plan)
  }

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: BeamTime, modes: Vector[BeamMode]) : ProfileRequest = {
    val profileRequest = new ProfileRequest()
    //Set timezone to timezone of transport network
    profileRequest.zoneId = transportNetwork.getTimeZone

    val fromPosTransformed = GeoUtils.transform.Utm2Wgs(fromFacility.getCoord)
    val toPosTransformed = GeoUtils.transform.Utm2Wgs(toFacility.getCoord)

    profileRequest.fromLon = fromPosTransformed.getX
    profileRequest.fromLat = fromPosTransformed.getY
    profileRequest.toLon = toPosTransformed.getX
    profileRequest.toLat = toPosTransformed.getY
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4

    val time = departureTime match {
      case time: DiscreteTime => WindowTime(time.atTime, beamServices.beamConfig.beam.routing.r5)
      case time: WindowTime => time
    }
    profileRequest.fromTime = time.fromTime
    profileRequest.toTime = time.toTime
    profileRequest.date = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate).toLocalDate

    val legModes : Vector[LegMode] = (for(m <- modes if isR5LegMode(m)) yield m.r5Mode.get.left.get)
    profileRequest.directModes = util.EnumSet.copyOf( legModes.asJavaCollection )

    val isTransit = legModes.size < modes.size

    if(isTransit){
      val transitModes : Vector[TransitModes] = (for(m <- modes if isR5TransitMode(m)) yield m.r5Mode.get.right.get)
      profileRequest.transitModes = util.EnumSet.copyOf(transitModes.asJavaCollection)
      profileRequest.accessModes = profileRequest.directModes
      profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    }

    profileRequest
  }

  def toBaseMidnightSecond(time: ZonedDateTime): Long = {
    val baseDate = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate)
//    (time.getDayOfYear - baseDate.getDayOfYear) * 86400 + time.getHour * 3600 + time.getMinute * 60 + time.getSecond
    ChronoUnit.SECONDS.between(time, baseDate)
  }

  def buildResponse(plan: ProfileResponse): RoutingResponse = {
//    RoutingResponse((for(option: ProfileOption <- plan.options.asScala) yield
//      BeamTrip( (for((itinerary, access) <- option.itinerary.asScala zip option.access.asScala) yield
//        BeamLeg(toBaseMidnightSecond(itinerary.startTime), BeamMode.withValue(access.mode.name()), itinerary.duration, null)
//      ).toVector)
//    ).toVector)

    RoutingResponse(plan.options.asScala.map(option =>
      BeamTrip( (for((itinerary, access) <- option.itinerary.asScala zip option.access.asScala) yield
        BeamLeg(toBaseMidnightSecond(itinerary.startTime), access.mode match {
          case LegMode.BICYCLE | LegMode.BICYCLE_RENT => BeamMode.BIKE
          case LegMode.WALK => BeamMode.WALK
          case LegMode.CAR | LegMode.CAR_PARK => BeamMode.CAR
        }, itinerary.duration, buildGraphPath(access), None)
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

  override def getPerson(personId: Id[PersonAgent]): Person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
  def isR5TransitMode(beamMode: BeamMode): Boolean = {
    beamMode.r5Mode match {
      case Some(Right(_)) =>
        true
      case _ => false
    }
  }
  def isR5LegMode(beamMode: BeamMode): Boolean = {
    beamMode.r5Mode match {
      case Some(Left(_)) =>
        true
      case _ => false
    }
  }
}

object R5RoutingWorker extends HasProps {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = null

  override def props(beamServices: BeamServices) = Props(classOf[R5RoutingWorker], beamServices)
}