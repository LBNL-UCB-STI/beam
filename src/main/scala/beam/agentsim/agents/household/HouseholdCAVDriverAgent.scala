package beam.agentsim.agents.household

import akka.actor.{ActorContext, ActorRef, ActorSelection, Props}
import akka.actor.FSM.Failure
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.PersonAgent.{DrivingData, PassengerScheduleEmpty, VehicleStack, WaitingToDrive}
import beam.agentsim.agents.TransitDriverAgent.TransitDriverData
import beam.agentsim.agents.household.HouseholdCAVDriverAgent.HouseholdCAVDriverData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, StartLegTrigger}
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.scheduler.BeamAgentScheduler._
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.model.BeamLeg
import beam.sim.BeamServices
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
  val vehicle: BeamVehicle
) extends DrivesVehicle[HouseholdCAVDriverData] {

  override val id: Id[HouseholdCAVDriverAgent] = driverId

  val myUnhandled: StateFunction = {
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(Finish, _) =>
      stop
  }

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, HouseholdCAVDriverData(null))

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      beamVehicles.put(vehicle.id, ActualVehicle(vehicle))
      vehicle.becomeDriver(self)
      eventsManager.processEvent(
        new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_household_cav_driver")
      )
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
      goto(WaitingToDrive) using data
        .copy(currentVehicle = Vector(vehicle.id))
        .asInstanceOf[HouseholdCAVDriverData] replying
      CompletionNotice(
        triggerId,
        Vector()
      )
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(_, _), _) =>
      stay
    case Event(TriggerWithId(KillTrigger(_), triggerId), _) =>
      scheduler ! CompletionNotice(triggerId)
      stop
  }

  override def logPrefix(): String = s"TransitDriverAgent:$id "

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

}

object HouseholdCAVDriverAgent {

  def props(
             driverId: Id[HouseholdCAVDriverAgent],
             scheduler: ActorRef,
             services: BeamServices,
             eventsManager: EventsManager,
             parkingManager: ActorRef,
             vehicle: BeamVehicle,
             legs: Seq[BeamLeg]
           ): Props = {
    Props(
      new HouseholdCAVDriverAgent(
        driverId,
        scheduler,
        services,
        eventsManager,
        parkingManager,
        vehicle
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
