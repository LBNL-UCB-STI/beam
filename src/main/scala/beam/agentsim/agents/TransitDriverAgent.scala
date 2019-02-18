package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorContext, ActorRef, ActorSelection, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{DrivingData, PassengerScheduleEmpty, VehicleStack, WaitingToDrive}
import beam.agentsim.agents.TransitDriverAgent.TransitDriverData
import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{ActualVehicle, StartLegTrigger}
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

/**
  * BEAM
  */
object TransitDriverAgent {

  def props(
    scheduler: ActorRef,
    services: BeamServices,
    transportNetwork: TransportNetwork,
    tollCalculator: TollCalculator,
    eventsManager: EventsManager,
    parkingManager: ActorRef,
    transitDriverId: Id[TransitDriverAgent],
    vehicle: BeamVehicle,
    legs: Seq[BeamLeg]
  ): Props = {
    Props(
      new TransitDriverAgent(
        scheduler,
        services,
        transportNetwork,
        tollCalculator,
        eventsManager,
        parkingManager,
        transitDriverId,
        vehicle,
        legs
      )
    )
  }

  def selectByVehicleId(
    transitVehicle: Id[Vehicle]
  )(implicit context: ActorContext): ActorSelection = {
    context.actorSelection("/user/router/" + createAgentIdFromVehicleId(transitVehicle))
  }

  def createAgentIdFromVehicleId(transitVehicle: Id[Vehicle]): Id[TransitDriverAgent] = {
    Id.create(
      "TransitDriverAgent-" + BeamVehicle.noSpecialChars(transitVehicle.toString),
      classOf[TransitDriverAgent]
    )
  }

  case class TransitDriverData(
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

class TransitDriverAgent(
  val scheduler: ActorRef,
  val beamServices: BeamServices,
  val transportNetwork: TransportNetwork,
  val tollCalculator: TollCalculator,
  val eventsManager: EventsManager,
  val parkingManager: ActorRef,
  val transitDriverId: Id[TransitDriverAgent],
  val vehicle: BeamVehicle,
  val legs: Seq[BeamLeg]
) extends DrivesVehicle[TransitDriverData] {

  override val id: Id[TransitDriverAgent] = transitDriverId

  val myUnhandled: StateFunction = {
    case Event(IllegalTriggerGoToError(reason), _) =>
      stop(Failure(reason))
    case Event(Finish, _) =>
      stop
    case Event(StopEvent, _) =>
      stop
  }

  override def logDepth: Int = beamServices.beamConfig.beam.debug.actor.logDepth

  startWith(Uninitialized, TransitDriverData(null))

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      logDebug(s" $id has been initialized, going to Waiting state")
      beamVehicles.put(vehicle.id, ActualVehicle(vehicle))
      vehicle.becomeDriver(self)
      eventsManager.processEvent(
        new PersonDepartureEvent(tick, Id.createPersonId(id), Id.createLinkId(""), "be_a_transit_driver")
      )
      eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
      val schedule = data.passengerSchedule.addLegs(legs)
      goto(WaitingToDrive) using data
        .copy(currentVehicle = Vector(vehicle.id))
        .withPassengerSchedule(schedule)
        .asInstanceOf[TransitDriverData] replying
      CompletionNotice(
        triggerId,
        Vector(
          ScheduleTrigger(
            StartLegTrigger(schedule.schedule.firstKey.startTime, schedule.schedule.firstKey),
            self
          )
        )
      )
  }

  when(PassengerScheduleEmpty) {
    // We are done, but we don't stop ourselves immediately.
    // Instead, we ask the scheduler to be notified after the
    // concurrency time window has passed, and then stop.
    // This is because other agents may still want to interact with us until then.
    case Event(PassengerScheduleEmptyMessage(_, _), _) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      scheduler ! ScheduleKillTrigger(self)
      scheduler ! CompletionNotice(triggerId)
      stay
    case Event(TriggerWithId(KillTrigger(_), triggerId), _) =>
      scheduler ! CompletionNotice(triggerId)
      stop
  }

  override def logPrefix(): String = s"TransitDriverAgent:$id "

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

}
