package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.{ActorRef, Props}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.WaitingToDrive
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.TriggerWithId
import beam.router.RoutingModel
import beam.router.RoutingModel.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.{PersonDepartureEvent, PersonEntersVehicleEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager

object RideHailingAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, scheduler: ActorRef, transportNetwork: TransportNetwork, eventsManager: EventsManager, rideHailingAgentId: Id[RideHailingAgent], vehicle: BeamVehicle, location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, scheduler, vehicle, location, eventsManager, services, transportNetwork))

  case class RideHailingAgentData() extends BeamAgentData

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
  override val data: RideHailingAgentData = RideHailingAgentData()
  override def logPrefix(): String = s"RideHailingAgent $id: "

  when(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _: BeamAgentInfo[RideHailingAgentData]) =>
      vehicle.becomeDriver(self).fold(fa =>
        stop(Failure(s"RideHailingAgent $self attempted to become driver of vehicle ${vehicle.id} " +
          s"but driver ${vehicle.driver.get} already assigned.")), fb => {
        _currentVehicleUnderControl = Some(vehicle)
        vehicle.checkInResource(Some(SpaceTime(initialLocation,tick.toLong)),context.dispatcher)
        eventsManager.processEvent(new PersonDepartureEvent(tick, Id.createPersonId(id), null, "be_a_tnc_driver"))
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        goto(WaitingToDrive) replying completed(triggerId)
      })
  }

  override def passengerScheduleEmpty(tick: Double, triggerId: Long) = {
    vehicle.checkInResource(Some(lastVisited),context.dispatcher)
    scheduler ! completed(triggerId)
    stay
  }

  whenUnhandled {
    case Event (Finish, _) =>
      stop
  }

}


