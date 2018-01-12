package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{Moving, PassengerScheduleEmptyTrigger, Waiting}
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.RideHailingManager.RideAvailableAck
import beam.agentsim.agents.TriggerUtils.{completed, _}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, ModifyPassengerScheduleAck, PassengerSchedule, ReservationResponse}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTrip, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.concurrent.ExecutionContext.Implicits.global

object RideHailingAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, transportNetwork: TransportNetwork, eventsManager: EventsManager, rideHailingAgentId: Id[RideHailingAgent], vehicle: BeamVehicle, location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, vehicle, location, eventsManager, services, transportNetwork))

  case class RideHailingAgentData() extends BeamAgentData

  case object Idle extends BeamAgentState

  case object Traveling extends BeamAgentState

  case class PickupCustomer(confirmation: ReservationResponse, customerId: Id[Person], pickUpLocation: Location, destination: Location, trip2DestPlan: Option[BeamTrip], trip2CustPlan: Option[BeamTrip])

  case class DropOffCustomer(newLocation: SpaceTime)

  def isRideHailingLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Vector[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailingLeg(l))
  }

  def isRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Boolean = {
    getRideHailingTrip(chosenTrip).nonEmpty
  }
  def exchangeVehicleId(vehicleId: Id[Vehicle]): Id[Vehicle] ={
    Id.createVehicleId(s"rideHailingVehicle-${vehicleId.toString}")
  }

}

class RideHailingAgent(override val id: Id[RideHailingAgent], vehicle: BeamVehicle, initialLocation: Coord,
                       val eventsManager: EventsManager, val beamServices: BeamServices, val transportNetwork: TransportNetwork)
  extends BeamAgent[RideHailingAgentData]
    with HasServices
    with DrivesVehicle[RideHailingAgentData] {
  override val data: RideHailingAgentData = RideHailingAgentData()
  override def logPrefix(): String = s"RideHailingAgent $id: "

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _: BeamAgentInfo[RideHailingAgentData]) =>
      vehicle.becomeDriver(self).fold(fa =>
        stop(Failure(s"RideHailingAgent $self attempted to become driver of vehicle ${vehicle.id} " +
          s"but driver ${vehicle.driver.get} already assigned.")), fb => {
        completeBecomingDriver(None, vehicle.id)
        vehicle.checkInResource(Some(SpaceTime(initialLocation,tick.toLong)),context.dispatcher)
        eventsManager.processEvent(new PersonEntersVehicleEvent(tick, Id.createPersonId(id), vehicle.id))
        goto(PersonAgent.Waiting) replying completed(triggerId)
      })
  }

  override def passengerScheduleEmptyActions(triggerId: Long, tick: Double): State = {
    vehicle.checkInResource(Some(lastVisited),context.dispatcher)
    stay replying completed(triggerId)
  }

  chainedWhen (AnyState) {
    case Event (ModifyPassengerScheduleAck (Some (msgId) ), _) =>
      stay
    case Event (Finish, _) =>
      stop
  }


  //// BOILERPLATE /////

  when (Waiting) {
  case ev@Event (_, _) =>
  handleEvent (stateName, ev)
  case msg@_ =>
  stop (Failure (s"Unrecognized message $msg") )
}

  when (Moving) {
  case ev@Event (_, _) =>
  handleEvent (stateName, ev)
  case msg@_ =>
  stop (Failure (s"Unrecognized message $msg") )
}

  when (AnyState) {
  case ev@Event (_, _) =>
  handleEvent (stateName, ev)
  case msg@_ =>
  stop (Failure (s"Unrecognized message $msg") )
}

}


