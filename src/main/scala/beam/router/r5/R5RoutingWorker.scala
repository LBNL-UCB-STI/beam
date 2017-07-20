package beam.router.r5

import java.io.File
import java.nio.file.Files.exists
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util

import akka.actor.Props
import beam.agentsim.agents.PersonAgent
import beam.router.BeamRouter.RoutingResponse
import beam.router.Modes._
import beam.router.RoutingModel._
import beam.router.RoutingWorker
import beam.router.RoutingWorker.HasProps
import beam.router.r5.R5RoutingWorker.{GRAPH_FILE, transportNetwork}
import beam.sim.BeamServices
import beam.utils.GeoUtils
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import com.vividsolutions.jts.geom.LineString
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._

class R5RoutingWorker(beamServices: BeamServices) extends RoutingWorker {
  //TODO this needs to be inferred from the TransitNetwork or configured
  val localDateAsString: String = "2016-10-17"
  val baseTime: Long = ZonedDateTime.parse(localDateAsString + "T00:00:00-07:00[UTC-07:00]").toEpochSecond
  //TODO make this actually come from beamConfig
//  val graphPathOutputsNeeded = beamServices.beamConfig.beam.outputs.writeGraphPathTraversals
  val graphPathOutputsNeeded = true

  override var services: BeamServices = beamServices

  override def init: Unit = loadMap

  def loadMap = {
    val networkDir = beamServices.beamConfig.beam.routing.r5.directory
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

  def buildResponse(plan: ProfileResponse): RoutingResponse = {

    var trips = Vector[BeamTrip]()
    for(option <- plan.options.asScala){
      /**
        * Iterating all itinerary from a ProfileOption to construct the BeamTrip,
        * itinerary has a PointToPointConnection object that help relating access,
        * egress and transit for the particular itinerary. That contains indexes of
        * access and egress and actual object anc be located from lists under option object,
        * as there are separate collections for each.
        *
        * And after locating through these indexes, constructing BeamLeg for each and
        * finally add these legs back to BeamTrip.
        */
      for(itinerary <- option.itinerary.asScala) {
        var legs = Vector[BeamLeg]()

        val access = option.access.get(itinerary.connection.access)

        // TODO Need to figure out vehicle id for access, egress, middle, transit and specify as last argument of BeamLeg
        legs = legs :+ BeamLeg(toBaseMidnightSecond(itinerary.startTime), mapLegMode(access.mode), access.duration, buildGraphPath(access))

        if(option.transit != null) {
          for (transitSegment <- option.transit.asScala) {
            for (segmentPattern <- transitSegment.segmentPatterns.asScala) {
              // TODO when this is the last SegmentPattern, we should use the toArrivalTime instead of the toDepartureTime
              // TODO we should convert the toIndex from just an index to the actual ID
              val toStopId: String = transportNetwork.transitLayer.stopIdForIndex.get(segmentPattern.toIndex)
              legs = legs :+ new BeamLeg(toBaseMidnightSecond(segmentPattern.fromDepartureTime.get(0)),
                mapTransitMode(transitSegment.mode),
                segmentPattern.toDepartureTime.get(0).toEpochSecond - segmentPattern.fromDepartureTime.get(0).toEpochSecond,
                buildGraphPath(transitSegment),
                beamVehicleId = Some(Id.createVehicleId(segmentPattern.routeIndex.toString)),
                endStopId = Some(toStopId))
            }
          }
        }

        if(itinerary.connection.egress != null) {
          val egress = option.egress.get(itinerary.connection.egress)
          //TODO find out what time and duration should use with egress legs
          legs = legs :+ BeamLeg(toBaseMidnightSecond(itinerary.startTime), mapLegMode(egress.mode), egress.duration, buildGraphPath(egress))
        }
        trips = trips :+ BeamTrip(legs)
      }
    }
    RoutingResponse(trips)
  }

  private def buildGraphPath(segment: StreetSegment): BeamGraphPath = {
    if(graphPathOutputsNeeded){
      BeamGraphPath.empty
    }else{
      var activeLinkIds = Vector[String]()
      //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
      var activeCoords = Vector[Coord]()
      var activeTimes = Vector[Long]()
      for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
        activeLinkIds = activeLinkIds :+ edge.edgeId.toString
        activeCoords = activeCoords :+ toCoord(edge.geometry)
      }
      BeamGraphPath(activeLinkIds, activeCoords, activeTimes)
    }
  }

  private def buildGraphPath(segment: TransitSegment): BeamGraphPath = {
    if(graphPathOutputsNeeded){
      BeamGraphPath.empty
    }else {
      var activeLinkIds = Vector[String]()
      //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
      var activeCoords = Vector[Coord]()
      var activeTimes = Vector[Long]()
      activeLinkIds = activeLinkIds :+ segment.from.stopId
      activeLinkIds = activeLinkIds :+ segment.to.stopId
      activeCoords = activeCoords :+ new Coord(segment.from.lon, segment.from.lat)
      activeCoords = activeCoords :+ new Coord(segment.to.lon, segment.to.lat)
      //    activeTimes = activeTimes :+ segment.segmentPatterns.get(0).fromDepartureTime

      //    for(pattern: SegmentPattern <- segment.segmentPatterns.asScala) {
      //      activeLinkIds = activeLinkIds :+ pattern.fromIndex.toString
      //      activeTimes = activeTimes :+ pattern.fromDepartureTime.
      //      activeCoords = activeCoords :+ toCoord(route.geometry)
      //    }
      BeamGraphPath(activeLinkIds, activeCoords, activeTimes)
    }
  }

  private def buildPath(profileRequest: ProfileRequest, streetMode: StreetMode): BeamGraphPath = {
    if(graphPathOutputsNeeded){
      BeamGraphPath.empty
    }else {

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

  private def toBaseMidnightSecond(time: ZonedDateTime): Long = {
    val baseDate = ZonedDateTime.parse(beamServices.beamConfig.beam.routing.baseDate)
    ChronoUnit.SECONDS.between(baseDate, time)
  }

  private def toCoord(geometry: LineString): Coord = {
    new Coord(geometry.getCoordinate.x, geometry.getCoordinate.y, geometry.getCoordinate.z)
  }

  override def getPerson(personId: Id[PersonAgent]): Person = beamServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
}

object R5RoutingWorker extends HasProps {
  val GRAPH_FILE = "/network.dat"

  var transportNetwork: TransportNetwork = null

  override def props(beamServices: BeamServices) = Props(classOf[R5RoutingWorker], beamServices)
}