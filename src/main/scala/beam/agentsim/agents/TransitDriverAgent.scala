package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorContext, ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.WaitingToDrive
import beam.agentsim.agents.TransitDriverAgent.TransitDriverData
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.modalBehaviors.DrivesVehicle.StartLegTrigger
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.scheduler.BeamAgentScheduler.IllegalTriggerGoToError
import beam.agentsim.scheduler.TriggerWithId
import beam.router.RoutingModel.BeamLeg
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

/**
  * BEAM
  */
object TransitDriverAgent {
  def props(scheduler: ActorRef, services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager,  transitDriverId: Id[TransitDriverAgent], vehicle: BeamVehicle,
            legs: Seq[BeamLeg]): Props = {
    Props(new TransitDriverAgent(scheduler, services, transportNetwork, eventsManager, transitDriverId, vehicle, legs))
  }

  case class TransitDriverData()

  def createAgentIdFromVehicleId(transitVehicle: Id[Vehicle]): Id[TransitDriverAgent] = {
    Id.create("TransitDriverAgent-" + BeamVehicle.noSpecialChars(transitVehicle.toString), classOf[TransitDriverAgent])
  }

  def selectByVehicleId(transitVehicle: Id[Vehicle])(implicit context: ActorContext) = {
    context.actorSelection("/user/router/" + createAgentIdFromVehicleId(transitVehicle))
  }
}

class TransitDriverAgent(val scheduler: ActorRef, val beamServices: BeamServices,
                         val transportNetwork: TransportNetwork,
                         val eventsManager: EventsManager,
                         val transitDriverId: Id[TransitDriverAgent],
                         val vehicle: BeamVehicle,
                         val legs: Seq[BeamLeg]) extends
  BeamAgent[TransitDriverData] with HasServices with DrivesVehicle[TransitDriverData] {
  override val id: Id[TransitDriverAgent] = transitDriverId

  override def logPrefix(): String = s"TransitDriverAgent:$id "

  val initialPassengerSchedule = PassengerSchedule()
  initialPassengerSchedule.addLegs(legs)

  startWith(Uninitialized, BeamAgentInfo(id, TransitDriverData()))

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[TransitDriverData]) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      vehicle.becomeDriver(self).fold(fa =>
        stop(Failure(s"BeamAgent $id attempted to become driver of vehicle $id " +
          s"but driver ${vehicle.driver.get} already assigned.")), fb => {
        _currentVehicleUnderControl = Some(vehicle)
        passengerSchedule = initialPassengerSchedule
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_transit_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        goto(WaitingToDrive) replying
          completed(triggerId, schedule[StartLegTrigger](passengerSchedule.schedule.firstKey.startTime, self, passengerSchedule.schedule.firstKey))
      })
  }

  val myUnhandled: StateFunction = {
    case Event(IllegalTriggerGoToError(reason), _)  =>
      stop(Failure(reason))
    case Event(Finish, _) =>
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

  override def passengerScheduleEmpty(tick: Double, triggerId: Long): State = {
    scheduler ! completed(triggerId)
    stop
  }
}
