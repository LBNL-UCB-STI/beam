package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit
import akka.actor.Status.{Failure, Success}
import akka.actor.ActorRef
import akka.pattern.pipe
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor.{
  GetVehicleTypes,
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle,
  ReleaseVehicleAndReply,
  VehicleTypesResponse
}
import beam.agentsim.agents.household.HouseholdFleetManager.ResolvedParkingResponses
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.HasTriggerId
import beam.sim.config.BeamConfig.Beam.Debug
import beam.utils.logging.{ExponentialLazyLogging, LoggingMessageActor}
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.{Coord, Id}

import scala.concurrent.{ExecutionContext, Future}

class HouseholdFleetManager(
  parkingManager: ActorRef,
  vehicles: Map[Id[BeamVehicle], BeamVehicle],
  homeCoord: Coord,
  implicit val debug: Debug,
) extends LoggingMessageActor
    with ExponentialLazyLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private var availableVehicles: List[BeamVehicle] = Nil

  var triggerSender: Option[ActorRef] = None

  override def loggedReceive: Receive = {
    case ResolvedParkingResponses(triggerId, xs) =>
      logger.debug(s"ResolvedParkingResponses ($triggerId, $xs)")
      xs.foreach {
        case (id, resp) =>
          val veh = vehicles(id)
          veh.setManager(Some(self))
          veh.spaceTime = SpaceTime(homeCoord.getX, homeCoord.getY, 0)
          veh.setMustBeDrivenHome(true)
          veh.useParkingStall(resp.stall)
          self ! ReleaseVehicleAndReply(veh, triggerId = triggerId)
      }
      triggerSender.foreach(actorRef => actorRef ! CompletionNotice(triggerId, Vector()))

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      triggerSender = Some(sender())
      val HasEnoughFuelToBeParked: Boolean = true
      val listOfFutures: List[Future[(Id[BeamVehicle], ParkingInquiryResponse)]] = vehicles.toList.map {
        case (id, _) =>
          (parkingManager ? ParkingInquiry(SpaceTime(homeCoord, 0), "init", triggerId = triggerId))
            .mapTo[ParkingInquiryResponse]
            .map { r =>
              (id, r)
            }
      }
      val futureOfList = Future.sequence(listOfFutures)
      val response = futureOfList.map(ResolvedParkingResponses(triggerId, _))
      response.pipeTo(self)

    case NotifyVehicleIdle(vId, whenWhere, _, _, _, _) =>
      val vehId = vId.asInstanceOf[Id[BeamVehicle]]
      vehicles(vehId).spaceTime = whenWhere
      logger.debug("updated vehicle {} with location {}", vehId, whenWhere)

    case ReleaseVehicle(vehicle, _) =>
      vehicle.unsetDriver()
      if (availableVehicles.contains(vehicle)) {
        logger.warn("I can't release vehicle {} because I have it already", vehicle.id)
      } else {
        availableVehicles = vehicle :: availableVehicles
        logger.debug("Vehicle {} is now available", vehicle.id)
      }

    case ReleaseVehicleAndReply(vehicle, _, _) =>
      vehicle.unsetDriver()
      if (availableVehicles.contains(vehicle)) {
        sender ! Failure(new RuntimeException(s"I can't release vehicle ${vehicle.id} because I have it already"))
      } else {
        availableVehicles = vehicle :: availableVehicles
        logger.debug("Vehicle {} is now available", vehicle.id)
        sender() ! Success
      }

    case GetVehicleTypes(triggerId) =>
      sender() ! VehicleTypesResponse(vehicles.values.map(_.beamVehicleType).toSet, triggerId)

    case MobilityStatusInquiry(_, _, _, triggerId) =>
      availableVehicles = availableVehicles match {
        case firstVehicle :: rest =>
          logger.debug("Vehicle {} is now taken", firstVehicle.id)
          firstVehicle.becomeDriver(sender)
          sender() ! MobilityStatusResponse(Vector(ActualVehicle(firstVehicle)), triggerId)
          rest
        case Nil =>
          sender() ! MobilityStatusResponse(Vector(), triggerId)
          Nil
      }

    case Finish =>
      context.stop(self)

    case Success =>
    case x =>
      logger.warn(s"No handler for ${x}")
  }
}

object HouseholdFleetManager {
  case class ResolvedParkingResponses(triggerId: Long, xs: List[(Id[BeamVehicle], ParkingInquiryResponse)])
      extends HasTriggerId
}
