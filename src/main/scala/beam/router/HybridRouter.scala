package beam.router

import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Access
import beam.router.Modes.BeamMode.WALK
import beam.router.graphhopper.{
  BikeGraphHopperWrapper,
  CarGraphHopperWrapper,
  GraphHopperWrapper,
  WalkGraphHopperWrapper
}
import beam.router.gtfs.GTFSUtils
import beam.router.model.EmbodiedBeamTrip
import beam.router.r5.R5Wrapper
import beam.sim.common.GeoUtils
import com.conveyal.gtfs.GTFSFeed
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Coord

import scala.collection.JavaConverters._

/**
  * @author Dmitry Openkov
  */
class HybridRouter(
  gtfs: IndexedSeq[GTFSFeed],
  geo: GeoUtils,
  r5: R5Wrapper,
  ghCar: CarGraphHopperWrapper,
  ghWalk: WalkGraphHopperWrapper,
  ghBike: BikeGraphHopperWrapper,
) extends Router
    with StrictLogging {

  private val stopQuadTree = GTFSUtils.toQuadTree(gtfs.flatMap(_.stops.values().asScala), geo)
  assert(stopQuadTree.size() > 0, "No stops in transit layer")

  private val ghRouters: IndexedSeq[(Modes.BeamMode, GraphHopperWrapper)] = IndexedSeq(
    Modes.BeamMode.CAR  -> ghCar,
    Modes.BeamMode.WALK -> ghWalk,
    Modes.BeamMode.BIKE -> ghBike,
  )

  override def calcRoute(request: BeamRouter.RoutingRequest): BeamRouter.RoutingResponse = {
    if (request.withTransit) {
      //find routes to the nearest stop via GH
      val stop = stopQuadTree.getClosest(request.originUTM.getX, request.originUTM.getY)
      val stopLocation = geo.wgs2Utm(new Coord(stop.stop_lon, stop.stop_lat))
      val ghRequest = request.copy(destinationUTM = stopLocation, withTransit = false)
      val ghResponses: IndexedSeq[BeamRouter.RoutingResponse] = calcRouteGH(ghRequest).map(_._2)
      val resultItineraries: IndexedSeq[EmbodiedBeamTrip] = if (ghResponses.nonEmpty) {
        //find routes from the nearest stop to the destination
        val bodyStreetVehicle = request.streetVehicles
          .filter(vehicle => vehicle.mode == WALK)
          .map(body => body.copy(locationUTM = SpaceTime(stopLocation, request.departureTime)))
        val r5Resp = r5.calcRoute(
          request
            .copy(originUTM = stopLocation, streetVehicles = bodyStreetVehicle, streetVehiclesUseIntermodalUse = Access)
        )
        for {
          it1 <- ghResponses.flatMap(_.itineraries)
          //we have to get only transit r5 routes where the first walk leg is a short one
          it2 <- r5Resp.itineraries
          if it2.tripClassifier != WALK && it2.legs.head.beamLeg.mode == WALK && it2.legs.head.beamLeg.duration < 20
        } yield {
          //we drop the first r5 WALK leg because we are walking/driving to the nearest stop vi GH route
          EmbodiedBeamTrip(it1.legs ++ it2.legs.tail)
        }
      } else {
        IndexedSeq.empty
      }
      if (resultItineraries.nonEmpty) {
        new BeamRouter.RoutingResponse(
          resultItineraries,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false
        )
      } else {
        r5.calcRoute(request)
      }
    } else {
      val ghResponses = calcRouteGH(request)

      //get r5 response only for vehicles that are not found by GH
      val modesWithResult = ghResponses.map { case (mode, _) => mode }
      val vehiclesForR5 = request.streetVehicles.filterNot(it => modesWithResult.contains(it.mode))
      val r5Response = if (vehiclesForR5.isEmpty) {
        None
      } else {
        Some(r5.calcRoute(request.copy(streetVehicles = vehiclesForR5)))
      }

      val allResponses = ghResponses.map { case (_, resp) => resp } ++ r5Response
      val itineraries = allResponses.flatMap(_.itineraries)

      BeamRouter.RoutingResponse(itineraries, request.requestId, Some(request), isEmbodyWithCurrentTravelTime = false)
    }

  }

  private def calcRouteGH(
    originRequest: BeamRouter.RoutingRequest
  ): IndexedSeq[(Modes.BeamMode, BeamRouter.RoutingResponse)] = {
    ghRouters
      .flatMap {
        case (mode, wrapper) =>
          originRequest.streetVehicles
            .find(_.mode == mode) //GH supports a single street vehicle only
            .map { vehicle =>
              mode -> wrapper.calcRoute(
                originRequest
                  .copy(streetVehicles = IndexedSeq(vehicle))
              )
            }
      }
      .filter { case (_, response) => response.itineraries.nonEmpty }
  }
}
