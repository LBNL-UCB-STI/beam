package beam.router

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Identify, Props, Stash, Terminated}
import akka.routing._
import akka.util.Timeout
import beam.agentsim.agents.{InitializeTrigger, PersonAgent, TransitDriverAgent}
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, StreetVehicle}
import beam.router.BeamRouter._
import beam.router.Modes.{BeamMode, isOnStreetTransit}
import beam.router.RoutingModel._
import beam.router.gtfs.FareCalculator
import beam.router.r5.{NetworkCoordinator, R5RoutingWorker}
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Identifiable}
import org.matsim.core.router.util.TravelTime

import scala.beans.BeanProperty
import scala.concurrent.Await
import akka.pattern._
import beam.agentsim.agents.vehicles.{BeamVehicle, Powertrain, TransitVehicle, TransitVehicleData}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.Modes.BeamMode.{BUS, CABLE_CAR, FERRY, RAIL, SUBWAY, TRAM}
import beam.router.r5.NetworkCoordinator.{beamPathBuilder, transportNetwork}
import com.conveyal.r5.api.util.{StreetEdgeInfo, StreetSegment}
import com.conveyal.r5.transit.{RouteInfo, TransitLayer}
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils, Vehicles}

import scala.collection.mutable
import scala.collection.JavaConverters._



class BeamRouter(services: BeamServices, transitVehicles: Vehicles, fareCalculator: FareCalculator) extends Actor with Stash with ActorLogging {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val networkCoordinator = context.actorOf(NetworkCoordinator.props(transitVehicles, services), "network-coordinator")

  // FIXME Wait for networkCoordinator because it initializes global variables.
  Await.ready(networkCoordinator ? Identify(0), timeout.duration)

  private val routerWorker = context.actorOf(R5RoutingWorker.props(services, fareCalculator), "router-worker")
  private var transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])] = Map()

  override def receive = {
    case InitTransit =>
      transitSchedule = initTransit()
      routerWorker ! TransitInited(transitSchedule)
      sender ! TransitInited(transitSchedule)
    case updateRequest: UpdateTravelTime =>
      routerWorker.forward(updateRequest)
    case w: RoutingRequest =>
      routerWorker.forward(w)
  }

  /*
* Plan of action:
* Each TripSchedule within each TripPattern represents a transit vehicle trip and will spawn a transitDriverAgent and a vehicle
* The arrivals/departures within the TripSchedules are vectors of the same length as the "stops" field in the TripPattern
* The stop IDs will be used to extract the Coordinate of the stop from the transitLayer (don't see exactly how yet)
* Also should hold onto the route and trip IDs and use route to lookup the transit agency which ultimately should
* be used to decide what type of vehicle to assign
*
*/
  def initTransit(): Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])] = {
    val stopToStopStreetSegmentCache = mutable.Map[(Int, Int), Option[StreetSegment]]()
    val transitTrips = transportNetwork.transitLayer.tripPatterns.asScala.toStream
    val transitData = transitTrips.flatMap { tripPattern =>
      val route = transportNetwork.transitLayer.routes.get(tripPattern.routeIndex)
      val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
      val transitPaths = tripPattern.stops.indices.sliding(2).map { case IndexedSeq(fromStopIdx, toStopIdx) =>
        val fromStop = tripPattern.stops(fromStopIdx)
        val toStop = tripPattern.stops(toStopIdx)
        if (isOnStreetTransit(mode)) {
          stopToStopStreetSegmentCache.getOrElseUpdate((fromStop, toStop), beamPathBuilder.routeTransitPathThroughStreets(fromStop, toStop)) match {
            case Some(streetSeg) =>
              var activeLinkIds = Vector[String]()
              for (edge: StreetEdgeInfo <- streetSeg.streetEdges.asScala) {
                activeLinkIds = activeLinkIds :+ edge.edgeId.toString
              }
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(activeLinkIds, Option(TransitStopsInfo(fromStopIdx, fromStop, vehicleId, toStopIdx, toStop)), StreetSegmentTrajectoryResolver(streetSeg, departureTime))
            case None =>
              val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStop, toStop)
              (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(edgeIds, Option(TransitStopsInfo(fromStopIdx, fromStop, vehicleId, toStopIdx, toStop)), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTime, duration))
          }
        } else {
          val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStop, toStop)
          (departureTime: Long, duration: Int, vehicleId: Id[Vehicle]) => BeamPath(edgeIds, Option(TransitStopsInfo(fromStopIdx, fromStop, vehicleId, toStopIdx, toStop)), TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureTime, duration))
        }
      }.toSeq
      val transitRouteTrips = tripPattern.tripSchedules.asScala
      transitRouteTrips.filter(_.getNStops > 0).map { transitTrip =>
        // First create a unique for this trip which will become the transit agent and vehicle ids
        val tripVehId = Id.create(transitTrip.tripId, classOf[Vehicle])
        val numStops = transitTrip.departures.length

        var legs: Seq[BeamLeg] = Nil
        if (numStops > 1) {
          val travelStops = transitTrip.departures.zipWithIndex.sliding(2)
          travelStops.foreach { case Array((departureTimeFrom, from), (depatureTimeTo, to)) =>
            val duration = transitTrip.arrivals(to) - departureTimeFrom
            //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
            //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
            legs :+= BeamLeg(departureTimeFrom.toLong, mode, duration, transitPaths(from)(departureTimeFrom.toLong, duration, tripVehId))
          }
        } else {
          log.warning(s"Transit trip  ${transitTrip.tripId} has only one stop ")
          val departureStart = transitTrip.departures(0)
          val fromStopIdx = tripPattern.stops(0)
          //XXX: inconsistency between Stop.stop_id and and data in stopIdForIndex, Stop.stop_id = stopIdForIndex + 1
          //XXX: we have to use data from stopIdForIndex otherwise router want find vehicle by beamleg in beamServices.transitVehiclesByBeamLeg
          val duration = 1L
          val edgeIds = beamPathBuilder.resolveFirstLastTransitEdges(fromStopIdx)
          val stopsInfo = TransitStopsInfo(0, 0, tripVehId, 0, 0)
          val transitPath = BeamPath(edgeIds, Option(stopsInfo),
            TrajectoryByEdgeIdsResolver(transportNetwork.streetLayer, departureStart.toLong, duration))
          legs :+= BeamLeg(departureStart.toLong, mode, duration, transitPath)
        }
        (tripVehId, (route, legs))
      }
    }
    val transitScheduleToCreate = transitData.filter(_._2._2.nonEmpty).toMap
    transitScheduleToCreate.foreach { case (tripVehId, (route, legs)) =>
      createTransitVehicle(tripVehId, route, legs)
    }
    log.info(s"Finished Transit initialization trips, ${transitData.length}")
    transitScheduleToCreate
  }

  def createTransitVehicle(transitVehId: Id[Vehicle], route: RouteInfo, legs: Seq[BeamLeg]) = {

    val mode = Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type))
    val vehicleTypeId = Id.create(mode.toString.toUpperCase + "-" + route.agency_id, classOf[VehicleType])

    val vehicleType = if (transitVehicles.getVehicleTypes.containsKey(vehicleTypeId)){
      transitVehicles.getVehicleTypes.get(vehicleTypeId);
    } else {
      log.info(s"no specific vehicleType available for mode and transit agency pair '${vehicleTypeId.toString})', using default vehicleType instead")
      transitVehicles.getVehicleTypes.get(Id.create(mode.toString.toUpperCase + "-DEFAULT", classOf[VehicleType]));
    }

    mode match {
      case (BUS | SUBWAY | TRAM | CABLE_CAR | RAIL | FERRY) if vehicleType != null =>
        val matSimTransitVehicle = VehicleUtils.getFactory.createVehicle(transitVehId, vehicleType)
        matSimTransitVehicle.getType.setDescription(mode.value)
        val consumption = Option(vehicleType.getEngineInformation).map(_.getGasConsumption).getOrElse(Powertrain.AverageMilesPerGallon)
        val transitVehProps = TransitVehicle.props(services, matSimTransitVehicle.getId, TransitVehicleData(), Powertrain.PowertrainFromMilesPerGallon(consumption), matSimTransitVehicle, new Attributes())
        val transitVehRef = context.actorOf(transitVehProps, BeamVehicle.buildActorName(matSimTransitVehicle))
        services.vehicles += (transitVehId -> matSimTransitVehicle)
        services.vehicleRefs += (transitVehId -> transitVehRef)
        services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitVehRef)

        val vehicleIdAndRef = BeamVehicleIdAndRef(transitVehId, transitVehRef)
        val transitDriverId = TransitDriverAgent.createAgentIdFromVehicleId(transitVehId)
        val transitDriverAgentProps = TransitDriverAgent.props(services, transitDriverId, vehicleIdAndRef, legs)
        val transitDriver = context.actorOf(transitDriverAgentProps, transitDriverId.toString)
        services.agentRefs += (transitDriverId.toString -> transitDriver)
        services.schedulerRef ! ScheduleTrigger(InitializeTrigger(0.0), transitDriver)

      case _ =>
        log.error(mode + " is not supported yet")
    }
  }

}

object BeamRouter {
  type Location = Coord

  def nextId = Id.create(UUID.randomUUID().toString, classOf[RoutingRequest])

  case object InitTransit
  case class TransitInited(transitSchedule: Map[Id[Vehicle], (RouteInfo, Seq[BeamLeg])])
  case class UpdateTravelTime(travelTime: TravelTime)

  /**
    * It is use to represent a request object
    * @param origin start/from location of the route
    * @param destination end/to location of the route
    * @param departureTime time in seconds from base midnight
    * @param transitModes what transit modes should be considered
    * @param streetVehicles what vehicles should be considered in route calc
    * @param personId
    */
  case class RoutingRequestTripInfo(origin: Location,
                                    destination: Location,
                                    departureTime: BeamTime,
                                    transitModes: Vector[BeamMode],
                                    streetVehicles: Vector[StreetVehicle],
                                    personId: Id[PersonAgent])

  /**
    * Message to request a route plan
    * @param id used to represent a request uniquely
    * @param params route information that is needs a plan
    */
  case class RoutingRequest(@BeanProperty id: Id[RoutingRequest],
                            params: RoutingRequestTripInfo) extends Identifiable[RoutingRequest]

  /**
    * Message to respond a plan against a particular router request
    * @param id same id that was send with request
    * @param itineraries a vector of planned routes
    */
  case class RoutingResponse(@BeanProperty id: Id[RoutingRequest],
                             itineraries: Vector[EmbodiedBeamTrip]) extends Identifiable[RoutingRequest]

  /**
    *
    * @param fromLocation
    * @param toOptions
    */
  case class BatchRoutingRequest(fromLocation: Location, toOptions: Vector[Location])

  object RoutingRequest {
    def apply(fromActivity: Activity, toActivity: Activity, departureTime: BeamTime, transitModes: Vector[BeamMode], streetVehicles: Vector[StreetVehicle], personId: Id[PersonAgent]): RoutingRequest = {
      new RoutingRequest(BeamRouter.nextId,
        RoutingRequestTripInfo(fromActivity.getCoord, toActivity.getCoord, departureTime,  Modes.filterForTransit(transitModes), streetVehicles, personId))
    }
    def apply(params : RoutingRequestTripInfo) = {
      new RoutingRequest(BeamRouter.nextId, params)
    }
  }

  def props(beamServices: BeamServices, transitVehicles: Vehicles, fareCalculator: FareCalculator) = Props(classOf[BeamRouter], beamServices, transitVehicles, fareCalculator)
}