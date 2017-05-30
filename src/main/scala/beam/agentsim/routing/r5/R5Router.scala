package beam.agentsim.routing.r5

import java.io.File
import java.util

import akka.actor.Props
import beam.agentsim.routing.BeamRouter
import beam.agentsim.routing.RoutingMessages._
import beam.agentsim.sim.AgentsimServices
import beam.agentsim.utils.GeoUtils
import com.conveyal.r5.api.util.{LegMode, TransitModes}
import com.conveyal.r5.point_to_point.builder.PointToPointQuery
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.population.Person
import org.matsim.facilities.Facility

import scala.collection.JavaConversions._

class R5Router(agentsimServices: AgentsimServices) extends BeamRouter {
  var transportNetwork: TransportNetwork = null

  override def receive: Receive = {
    case InitializeRouter =>
      //  InitializeRouter responding with RouterInitialized
      init
      sender() ! RouterInitialized()
    case RoutingRequest(fromFacility, toFacility, departureTime, personId) =>
      // RoutingRequest(fromFacility, toFacility, departureTime, personId) responding with RoutingResponse
      val person: Person = agentsimServices.matsimServices.getScenario.getPopulation.getPersons.get(personId)

      sender() ! calcRoute(fromFacility, toFacility, departureTime, person)
  }

  private def init = {
    //TODO: network.dat and its paths need to externalize
    loadGraph("/network.dat")
  }

  private def loadGraph(graphName: String): Unit = {
    //Loading graph
    val networkFile = new File(getClass.getResource(graphName).getFile)
    transportNetwork = TransportNetwork.read(networkFile)

//    Optional used to get street names:
//TODO: osm.mapdb and its paths need to externalize
    val mapdbFile = new File(getClass.getResource("osm.mapdb").getFile)
    transportNetwork.readOSM(mapdbFile)
  }

  private def calcRoute(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person) = {

    val streetRouter = new StreetRouter(transportNetwork.streetLayer)
    val profileRequest = buildRequest(fromFacility, toFacility, departureTime)
    streetRouter.profileRequest = profileRequest
    streetRouter.streetMode = StreetMode.WALK

    // TODO use target pruning instead of a distance limit
    streetRouter.distanceLimitMeters = 100000

    streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
    streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)

    streetRouter.route

    //Gets lowest weight state for end coordinate split
    val lastState = streetRouter.getState(streetRouter.getDestinationSplit())
    val streetPath = new StreetPath(lastState, transportNetwork)

    var totalDistance = 0
    for (state <- streetPath.getStates) {
      //      val edgeIdx = state.backEdge
      //      if (!(edgeIdx == -1 || edgeIdx == null)) {
      //        val edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx)
      //      }
      totalDistance = totalDistance + state.distance / 1000 //convert distance from mm to m
    }
    totalDistance
  }

  private def calcRoute2(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, person: Person) = {

    //Gets a response:
    val pointToPointQuery = new PointToPointQuery(transportNetwork)
    pointToPointQuery.getPlan(buildRequest(fromFacility, toFacility, departureTime))
  }

  private def buildRequest(fromFacility: Facility[_], toFacility: Facility[_], departureTime: Double, isTransit: Boolean = false) : ProfileRequest = {
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
}

object R5Router {
  def props(agentsimServices: AgentsimServices) = Props(classOf[R5Router], agentsimServices)
}