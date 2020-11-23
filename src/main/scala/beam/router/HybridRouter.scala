package beam.router

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.WALK
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.model.EmbodiedBeamTrip
import beam.router.r5.R5Wrapper
import beam.sim.common.GeoUtils
import beam.sim.common.GeoUtils.fromJtsCoordinate
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.HasCoord
import com.conveyal.gtfs.model.Stop
import com.conveyal.r5.transit.{TransitLayer, TransportNetwork}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.convert.ImplicitConversions.`collection AsScalaIterable`

/**
  * @author Dmitry Openkov
  */
class HybridRouter(
  transportNetwork: TransportNetwork,
  geo: GeoUtils,
  r5: R5Wrapper,
  ghCar: CarGraphHopperWrapper,
  ghWalk: WalkGraphHopperWrapper
) extends Router
    with StrictLogging {

  private val transitLayer: TransitLayer = transportNetwork.transitLayer
  assert(transitLayer.getStopCount > 0, "No stops in transit layer")

  private val ghRouters: List[(Modes.BeamMode, GraphHopperWrapper)] = List(
    Modes.BeamMode.CAR  -> ghCar,
    Modes.BeamMode.WALK -> ghWalk,
  )

  private val stopQuadTree: QuadTree[Location] = {
    implicit val locationHasCoord: HasCoord[Location] = (loc: Location) => loc
    val stopLocations = (0 until transitLayer.getStopCount)
      .map(transitLayer.getCoordinateForStopFixed)
      .flatMap(coordinate => if (coordinate == null) None else Some(fromJtsCoordinate(coordinate)))
    ShapeUtils.quadTree(stopLocations)
  }

  override def calcRoute(request: BeamRouter.RoutingRequest): BeamRouter.RoutingResponse = {
    if (request.withTransit) {
      //find routes to the nearest stop via GH
      val stopLocation = stopQuadTree.getClosest(request.originUTM.getX, request.originUTM.getY)
      val ghResponses: List[BeamRouter.RoutingResponse] = calcRouteGH(request, stopLocation).map(_._2)
      logger.error(s"ghResponses.size = ${ghResponses.size}") //todo remove it
      if (ghResponses.nonEmpty) {
        //find routes from the nearest stop to the destination
        val bodyStreetVehicle = request.streetVehicles
          .filter(vehicle => !vehicle.asDriver || vehicle.mode == WALK)
          .map(body => body.copy(locationUTM = SpaceTime(stopLocation, request.departureTime)))
        val r5Resp = r5.calcRoute(request.copy(originUTM = stopLocation, streetVehicles = bodyStreetVehicle))
        combine(ghResponses, r5Resp)
      } else {
        r5.calcRoute(request)
      }
    } else {
      val ghResponses = calcRouteGH(request, request.destinationUTM)

      val modesWithResult = ghResponses.map { case (mode, _) => mode }
      val filteredStreetVehicles = request.streetVehicles.filterNot(it => modesWithResult.contains(it.mode))

      //get r5 response only for vehicles that are not found by GH
      val r5Response = if (filteredStreetVehicles.isEmpty) {
        None
      } else {
        Some(r5.calcRoute(request.copy(streetVehicles = filteredStreetVehicles)))
      }

      val allResponses = ghResponses.map { case (_, resp) => resp } ++ r5Response
      val itineraries = allResponses.flatMap(_.itineraries)

      BeamRouter.RoutingResponse(itineraries, request.requestId, Some(request), isEmbodyWithCurrentTravelTime = false)
    }

  }

  def combine(
    ghResponses: List[BeamRouter.RoutingResponse],
    r5Resp: BeamRouter.RoutingResponse
  ): BeamRouter.RoutingResponse = {
    if (r5Resp.itineraries.isEmpty)
      r5Resp
    else {
      val itineraries = for {
        it1 <- ghResponses.flatMap(_.itineraries)
        it2 <- r5Resp.itineraries
      } yield EmbodiedBeamTrip(it1.legs ++ it2.legs)
      r5Resp.copy(itineraries = itineraries)
    }
  }

  private def utmCoord(a: Stop) = {
    geo.wgs2Utm(new Location(a.stop_lon, a.stop_lat))
  }

  private def calcRouteGH(originRequest: BeamRouter.RoutingRequest, destination: Location) = {
    ghRouters
      .flatMap {
        case (mode, wrapper) =>
          if (originRequest.streetVehicles.exists(_.mode == mode))
            Some(
              mode -> wrapper.calcRoute(
                originRequest
                  .copy(withTransit = false, streetVehicles = originRequest.streetVehicles.filter(_.mode == mode))
              )
            )
          else
            None
      }
      .filter { case (_, response) => response.itineraries.nonEmpty }
  }
}
