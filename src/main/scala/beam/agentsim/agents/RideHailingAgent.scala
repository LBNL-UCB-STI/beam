package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent._
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.RoutingModel
import beam.router.RoutingModel.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager

import scala.concurrent.duration.Duration

object RideHailingAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, scheduler: ActorRef, transportNetwork: TransportNetwork, eventsManager: EventsManager, rideHailingAgentId: Id[RideHailingAgent], vehicle: BeamVehicle, location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, scheduler, vehicle, location, eventsManager, services, transportNetwork))

  case class RideHailingAgentData(currentVehicle: VehicleStack = Vector(), passengerSchedule: PassengerSchedule = PassengerSchedule()) extends DrivingData {
    override def withPassengerSchedule(newPassengerSchedule: PassengerSchedule): DrivingData = copy(passengerSchedule = newPassengerSchedule)
  }

  def isRideHailingLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Vector[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailingLeg(l))
  }

}

class RideHailingAgent(override val id: Id[RideHailingAgent], val scheduler: ActorRef, vehicle: BeamVehicle, initialLocation: Coord,
                       val eventsManager: EventsManager, val beamServices: BeamServices, val transportNetwork: TransportNetwork)
  extends BeamAgent[RideHailingAgentData]
    with HasServices
    with DrivesVehicle[RideHailingAgentData] {
  override def logPrefix(): String = s"RideHailingAgent $id: "

  startWith(Uninitialized, RideHailingAgentData())

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), data) =>
      vehicle.becomeDriver(self).fold(fa =>
        stop(Failure(s"RideHailingAgent $self attempted to become driver of vehicle ${vehicle.id} " +
          s"but driver ${vehicle.driver.get} already assigned.")), fb => {
        vehicle.checkInResource(Some(SpaceTime(initialLocation,tick.toLong)),context.dispatcher)
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_tnc_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        goto(WaitingToDrive) replying CompletionNotice(triggerId) using data.copy(currentVehicle = Vector(vehicle.id))
      })
  }

  when(PassengerScheduleEmpty) {
    case Event(PassengerScheduleEmptyMessage(lastVisited), _) =>
      val (_, triggerId) = releaseTickAndTriggerId()
      vehicle.checkInResource(Some(lastVisited),context.dispatcher)
      scheduler ! CompletionNotice(triggerId)
      goto(WaitingToDrive) using stateData.withPassengerSchedule(PassengerSchedule()).asInstanceOf[RideHailingAgentData]
  }

  val myUnhandled: StateFunction =  {
    case Event (Finish, _) =>
      stop
  }

  whenUnhandled(drivingBehavior.orElse(myUnhandled))

}


