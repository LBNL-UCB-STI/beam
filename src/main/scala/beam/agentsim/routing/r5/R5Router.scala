package beam.agentsim.routing.r5

import java.io.File
import java.nio.file.Files.{exists, isReadable}
import java.nio.file.Paths.get
import java.util

import akka.actor.Props
import beam.agentsim.agents.PersonAgent
import beam.agentsim.config.BeamConfig
import beam.agentsim.core.Modes.BeamMode
import beam.agentsim.routing.BeamRouter
import beam.agentsim.routing.RoutingMessages._
import beam.agentsim.routing.RoutingModel.{BeamLeg, BeamTrip}
import beam.agentsim.sim.AgentsimServices
import beam.agentsim.utils.GeoUtils
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util.{LegMode, TransitModes}
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility

import scala.collection.JavaConverters._

class R5Router(agentsimServices: AgentsimServices, beamConfig : BeamConfig) extends BeamRouter {
  private val GRAPH_FILE = "/network.dat"
  private val OSM_FILE = "/osm.mapdb"
  private lazy val networkDir = beamConfig.beam.routing.otp.directory
  var transportNetwork: TransportNetwork = null

  override def loadMap = {
    var networkFile: File = null
    var mapdbFile: File = null
    if (exists(get(networkDir))) {
      val networkPath = get(networkDir, GRAPH_FILE)
      if (isReadable(networkPath)) networkFile = networkPath.toFile
      val osmPath = get(networkDir, OSM_FILE)
      if (isReadable(osmPath)) mapdbFile = osmPath.toFile
    }
    if (networkFile == null) networkFile = get(System.getProperty("user.home"),"beam", "network", GRAPH_FILE).toFile

    if (mapdbFile == null) mapdbFile = get(System.getProperty("user.home"),"beam", "network", OSM_FILE).toFile
    // Loading graph
    transportNetwork = TransportNetwork.read(networkFile)
    // Optional used to get street names:
    transportNetwork.readOSM(mapdbFile)
  }

  override def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person) = {
    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    val plan = pointToPointQuery.getPlan(buildRequest(fromFacility, toFacility, departureTime))
    buildResponse(plan)
  }

  def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, isTransit: Boolean = false) : ProfileRequest = {
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
    //TODO: time need to get from request
    profileRequest.setTime("2015-02-05T07:30+05:00", "2015-02-05T10:30+05:00")
    if(isTransit) {
      profileRequest.transitModes = util.EnumSet.of(TransitModes.TRANSIT, TransitModes.BUS, TransitModes.SUBWAY, TransitModes.RAIL)
    }
    profileRequest.accessModes = util.EnumSet.of(LegMode.WALK)
    profileRequest.egressModes = util.EnumSet.of(LegMode.WALK)
    profileRequest.directModes = util.EnumSet.of(LegMode.WALK, LegMode.BICYCLE)

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
        BeamLeg(itinerary.startTime.toEpochSecond, BeamMode.withValue(access.mode.name()), itinerary.duration, null)
        ).toVector)
      ).toVector)
  }

  private def buildPath(profileRequest: ProfileRequest) = {

    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = StreetMode.WALK

    // TODO use target pruning instead of a distance limit
    streetRouter.distanceLimitMeters = 100000

    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)

    streetRouter.route

    //Gets lowest weight state for end coordinate split
    val lastState = streetRouter.getState(streetRouter.getDestinationSplit())
    val streetPath = new StreetPath(lastState, transportNetwork, false)

    var stateIdx = 0
    var totalDistance = 0

    for (state <- streetPath.getStates.asScala) {
      val edgeIdx = state.backEdge
      if (!(edgeIdx == null || edgeIdx == -1)) {
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx)

        log.info("{} - EdgeIndex [{}]", stateIdx, edgeIdx)
        log.info("\t Lat/Long [{}]", edge.getGeometry)
        log.info("\tmode [{}]", state.streetMode)
        log.info("\tweight [{}]", state.weight)
        log.info("\tduration sec [{}:{}]", state.getDurationSeconds / 60, state.getDurationSeconds % 60)
        log.info("\tdistance [{}]", state.distance / 1000) //convert distance from mm to m
        stateIdx += 1
        totalDistance = state.distance / 1000
      }
    }
    new RoutingResponse(null)
  }

  override def getPerson(personId: Id[PersonAgent]): Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)
}

object R5Router {
  def props(agentsimServices: AgentsimServices) = Props(classOf[R5Router], agentsimServices)
}