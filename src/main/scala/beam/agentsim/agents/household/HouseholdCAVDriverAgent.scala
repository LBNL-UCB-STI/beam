package beam.agentsim.agents.household

import akka.actor.{ActorContext, ActorRef, ActorSelection, Props}
import akka.actor.FSM.Failure
import akka.actor.Status.Success
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.PersonAgent.{DrivingData, PassengerScheduleEmpty, VehicleStack, WaitingToDrive}
import beam.agentsim.agents.household.HouseholdActor.{ReleaseVehicle, ReleaseVehicleAndReply}
import beam.agentsim.agents.household.HouseholdCAVDriverAgent.HouseholdCAVDriverData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, StartLegTrigger}
import beam.agentsim.agents.ridehail.RideHailAgent.{Idle, ModifyPassengerSchedule, ModifyPassengerScheduleAck}
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.model.BeamLeg
import beam.router.osm.TollCalculator
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

class HouseholdCAVDriverAgent(
  val driverId: Id[HouseholdCAVDriverAgent],
  val scheduler: ActorRef,
  val beamServices: BeamServices,
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val vehicle: BeamVehicle,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator
) extends DrivesVehicle[HouseholdCAVDriverData] {

  override val id: Id[HouseholdCAVDriverAgent] = driverId

  val myUnhandled: StateFunction = {
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(ModifyPassengerSchedule(_, _, _), _) =>
      stash()
      stay()
    case Event(Finish, _) =>
      stop
  }
  onTransition {
    case _ -> _ =>
      unstashAll()
  }

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, HouseholdCAVDriverData(null))

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      beamVehicles.put(vehicle.id, ActualVehicle(vehicle))
      eventsManager.processEvent(
        new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_household_cav_driver")
      )
      goto(Idle) using data
        .copy(currentVehicle = Vector(vehicle.id))
        .asInstanceOf[HouseholdCAVDriverData] replying
      CompletionNotice(
        triggerId,
        Vector()
      )
  }
  when(Idle) {
    case ev @ Event(ModifyPassengerSchedule(updatedPassengerSchedule, tick, requestId), data) =>
      log.debug("state(RideHailingAgent.IdleInterrupted): {}", ev)
      // This is a message from another agent, the ride-hailing manager. It is responsible for "keeping the trigger",
      // i.e. for what time it is. For now, we just believe it that time is not running backwards.
      log.debug("updating Passenger schedule - vehicleId({}): {}", id, updatedPassengerSchedule)
      val triggerToSchedule = Vector(
        ScheduleTrigger(
          StartLegTrigger(
            updatedPassengerSchedule.schedule.firstKey.startTime,
            updatedPassengerSchedule.schedule.firstKey
          ),
          self
        )
      )
      if (id.toString.equals("cavDriver-63-0")) {
        val i = 0
      }
      goto(WaitingToDrive) using data
        .withPassengerSchedule(updatedPassengerSchedule)
        .withCurrentLegPassengerScheduleIndex(0)
        .asInstanceOf[HouseholdCAVDriverData] replying ModifyPassengerScheduleAck(
        requestId,
        triggerToSchedule,
        vehicle.id,
        tick
      )
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(lastVisited, _), _) =>
      log.debug(s"Releasing CAV at ${lastVisited.time}")
      vehicle.manager.get ! ReleaseVehicleAndReply(vehicle, Some(lastVisited.time))
      stay
    case Event(Success, _) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId)
      goto(Idle)

    case Event(TriggerWithId(KillTrigger(_), triggerId), _) =>
      scheduler ! CompletionNotice(triggerId)
      stop
  }

  override def logPrefix(): String = s"$id "

  whenUnhandled(drivingBehavior.orElse(myUnhandled))
}

object HouseholdCAVDriverAgent {

  def idFromVehicleId(vehId: Id[BeamVehicle]) = Id.create(s"cavDriver-$vehId", classOf[HouseholdCAVDriverAgent])

  def props(
    driverId: Id[HouseholdCAVDriverAgent],
    scheduler: ActorRef,
    services: BeamServices,
    eventsManager: EventsManager,
    parkingManager: ActorRef,
    vehicle: BeamVehicle,
    legs: Seq[BeamLeg],
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator
  ): Props = {
    Props(
      new HouseholdCAVDriverAgent(
        driverId,
        scheduler,
        services,
        eventsManager,
        parkingManager,
        vehicle,
        transportNetwork,
        tollCalculator
      )
    )
  }

  def selectByVehicleId(transitVehicle: Id[Vehicle])(implicit context: ActorContext): ActorSelection = {
    context.actorSelection("/user/population/household/" + createAgentIdFromVehicleId(transitVehicle))
  }

  def createAgentIdFromVehicleId(cavVehicle: Id[Vehicle]): Id[HouseholdCAVDriverAgent] = {
    Id.create(
      "HouseholdCAVDriverAgent-" + BeamVehicle.noSpecialChars(cavVehicle.toString),
      classOf[HouseholdCAVDriverAgent]
    )
  }

  case class HouseholdCAVDriverData(
    currentVehicleToken: BeamVehicle,
    currentVehicle: VehicleStack = Vector(),
    passengerSchedule: PassengerSchedule = PassengerSchedule(),
    currentLegPassengerScheduleIndex: Int = 0
  ) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData =
      copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(
      currentLegPassengerScheduleIndex: Int
    ): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)

    override def hasParkingBehaviors: Boolean = false
  }
}
