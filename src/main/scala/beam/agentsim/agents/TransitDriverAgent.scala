package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorContext, ActorRef, ActorSelection, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{DrivingData, PassengerScheduleEmpty, VehicleStack, WaitingToDrive}
import beam.agentsim.agents.TransitDriverAgent.TransitDriverData
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, IllegalTriggerGoToError, ScheduleTrigger}
import beam.agentsim.scheduler.TriggerWithId
import beam.router.RoutingModel.BeamLeg
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

/**
  * BEAM
  */
object TransitDriverAgent {
  def props(scheduler: ActorRef, services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager, transitDriverId: Id[TransitDriverAgent], vehicle: BeamVehicle,
            legs: Seq[BeamLeg]): Props = {
    Props(new TransitDriverAgent(scheduler, services, transportNetwork, eventsManager, transitDriverId, vehicle, legs))
  }

  case class TransitDriverData(currentVehicle: VehicleStack = Vector(), passengerSchedule: PassengerSchedule = PassengerSchedule(), currentLegPassengerScheduleIndex: Int = 0) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(passengerSchedule = newPassengerSchedule)

    override def withCurrentLegPassengerScheduleIndex(currentLegPassengerScheduleIndex: Int): DrivingData = copy(currentLegPassengerScheduleIndex = currentLegPassengerScheduleIndex)
  }

  def createAgentIdFromVehicleId(transitVehicle: Id[Vehicle]): Id[TransitDriverAgent] = {
    Id.create("TransitDriverAgent-" + BeamVehicle.noSpecialChars(transitVehicle.toString), classOf[TransitDriverAgent])
  }

  def selectByVehicleId(transitVehicle: Id[Vehicle])(implicit context: ActorContext) = {
    context.actorSelection("/user/BeamMobsim.iteration/" + createAgentIdFromVehicleId(transitVehicle))
  }
}

class TransitDriverAgent(val scheduler: ActorRef, val beamServices: BeamServices,
                         val transportNetwork: TransportNetwork,
                         val eventsManager: EventsManager,
                         val transitDriverId: Id[TransitDriverAgent],
                         val vehicle: BeamVehicle,
                         val legs: Seq[BeamLeg]) extends
  DrivesVehicle[TransitDriverData] {
  override val id: Id[TransitDriverAgent] = transitDriverId

  override def logPrefix(): String = s"TransitDriverAgent:$id "

  startWith(Uninitialized, TransitDriverData())

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      vehicle.becomeDriver(self).fold(_ =>
        stop(Failure(s"BeamAgent $id attempted to become driver of vehicle $id " +
          s"but driver ${vehicle.driver.get} already assigned.")), _ => {
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_transit_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        val schedule = data.passengerSchedule.addLegs(legs)
        goto(WaitingToDrive) using data.copy(currentVehicle = Vector(vehicle.id)).withPassengerSchedule(schedule).asInstanceOf[TransitDriverData] replying
          CompletionNotice(triggerId, Vector(ScheduleTrigger(StartLegTrigger(schedule.schedule.firstKey.startTime, schedule.schedule.firstKey), self)))
      })
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(_), _) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! CompletionNotice(triggerId)
      stop
  }

  val myUnhandled: StateFunction = {
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(Finish, _) =>
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

}
