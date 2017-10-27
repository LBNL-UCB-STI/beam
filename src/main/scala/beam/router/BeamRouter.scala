package beam.router

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Identify, Props, Stash, Terminated}
import akka.routing._
import akka.util.Timeout
import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.BeamVehicle.StreetVehicle
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamTime, EmbodiedBeamTrip}
import beam.router.gtfs.FareCalculator
import beam.router.r5.{NetworkCoordinator, R5RoutingWorker}
import beam.sim.BeamServices
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id, Identifiable}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

import scala.beans.BeanProperty
import scala.concurrent.Await
import akka.pattern._
import org.matsim.vehicles.Vehicles


class BeamRouter(services: BeamServices, transitVehicles: Vehicles, fareCalculator: FareCalculator) extends Actor with Stash with ActorLogging {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private var networkCoordinator = context.actorOf(NetworkCoordinator.props(transitVehicles, services), "network-coordinator")

  // FIXME Wait for networkCoordinator because it initializes global variables.
  Await.ready(networkCoordinator ? Identify(0), timeout.duration)

  private var routerWorker = context.actorOf(R5RoutingWorker.props(services, fareCalculator), "router-worker")

  override def receive = {
    case InitTransit =>
      networkCoordinator.forward(InitTransit)
    case updateRequest: UpdateTravelTime =>
      networkCoordinator.forward(updateRequest)
    case w: RoutingRequest =>
      routerWorker.forward(w)
  }
}

object BeamRouter {
  type Location = Coord

  def nextId = Id.create(UUID.randomUUID().toString, classOf[RoutingRequest])

  case object InitializeRouter
  case object RouterInitialized
  case object RouterNeedInitialization
  case object InitTransit
  case object TransitInited
  case class UpdateTravelTime(travelTimeCalculator: TravelTimeCalculator)

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