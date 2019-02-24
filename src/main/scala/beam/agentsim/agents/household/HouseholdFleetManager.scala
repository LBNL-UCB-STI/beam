package beam.agentsim.agents.household
import java.util.concurrent.TimeUnit

import akka.actor.Status.Success
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.household.HouseholdActor.{
  MobilityStatusInquiry,
  MobilityStatusResponse,
  ReleaseVehicle,
  ReleaseVehicleAndReply
}
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.ActualVehicle
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager.{ParkingInquiry, ParkingInquiryResponse}
import beam.agentsim.infrastructure.ParkingStall.NoNeed
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.{Coord, Id}

import scala.concurrent.{ExecutionContext, Future}
import akka.pattern.{ask, pipe}
import beam.agentsim.agents.BeamAgent.Finish

class HouseholdFleetManager(parkingManager: ActorRef, vehicles: Map[Id[BeamVehicle], BeamVehicle], homeCoord: Coord)
    extends Actor
    with ActorLogging {
  private implicit val timeout: Timeout = Timeout(50000, TimeUnit.SECONDS)
  private implicit val executionContext: ExecutionContext = context.dispatcher

  private var availableVehicles: List[BeamVehicle] = Nil

  override def receive: Receive = {

    case TriggerWithId(InitializeTrigger(_), triggerId) =>
      // Pipe my cars through the parking manager
      // and complete initialization only when I got them all.
      Future
        .sequence(vehicles.values.map { veh =>
          veh.manager = Some(self)
          veh.spaceTime = SpaceTime(homeCoord.getX, homeCoord.getY, 0)
          veh.mustBeDrivenHome = true
          parkingManager ? ParkingInquiry(
            homeCoord,
            homeCoord,
            "home",
            AttributesOfIndividual.EMPTY,
            NoNeed,
            0,
            0
          ) flatMap {
            case ParkingInquiryResponse(stall, _) =>
              veh.useParkingStall(stall)
              self ? ReleaseVehicleAndReply(veh)
          }
        })
        .map(_ => CompletionNotice(triggerId, Vector()))
        .pipeTo(sender())

    case NotifyVehicleIdle(vId, whenWhere, _, _, _) =>
      val vehId = vId.asInstanceOf[Id[BeamVehicle]]
      vehicles(vehId).spaceTime = whenWhere
      log.debug("updated vehicle {} with location {}", vehId, whenWhere)

    case ReleaseVehicle(vehicle) =>
      vehicle.unsetDriver()
      if (!availableVehicles.contains(vehicle)) {
        availableVehicles = vehicle :: availableVehicles
      }
      log.debug("Vehicle {} is now available", vehicle.id)

    case ReleaseVehicleAndReply(vehicle) =>
      vehicle.unsetDriver()
      if (!availableVehicles.contains(vehicle)) {
        availableVehicles = vehicle :: availableVehicles
      }
      log.debug("Vehicle {} is now available", vehicle.id)
      sender() ! Success

    case MobilityStatusInquiry(_) =>
      availableVehicles = availableVehicles match {
        case firstVehicle :: rest =>
          log.debug("Vehicle {} is now taken", firstVehicle.id)
          firstVehicle.becomeDriver(sender)
          sender() ! MobilityStatusResponse(Vector(ActualVehicle(firstVehicle)))
          rest
        case Nil =>
          sender() ! MobilityStatusResponse(Vector())
          Nil
      }

    case Finish =>
      context.stop(self)
  }
}
