package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{Moving, PassengerScheduleEmptyTrigger, Waiting}
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.RideHailingManager.RideAvailableAck
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.BeamVehicle.{BeamVehicleIdAndRef, BecomeDriver, BecomeDriverSuccessAck}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.resources.vehicle.{ModifyPassengerScheduleAck, ReservationResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTrip, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}

import scala.concurrent.ExecutionContext.Implicits.global

object RideHailingAgent {

  def props(services: BeamServices, rideHailingAgentId: Id[RideHailingAgent], vehicleIdAndRef: BeamVehicleIdAndRef, location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, RideHailingAgentData(vehicleIdAndRef, location), services))

  case class RideHailingAgentData(vehicleIdAndRef: BeamVehicleIdAndRef, location: Coord) extends BeamAgentData

  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }

  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }

  case class PickupCustomer(confirmation: ReservationResponse, customerId:Id[Person], pickUpLocation: Location, destination: Location, trip2DestPlan: Option[BeamTrip], trip2CustPlan: Option[BeamTrip])

  case class DropOffCustomer(newLocation: SpaceTime)

  case class RegisterRideAvailableWrapper(triggerId: Long)

  def isRideHailingLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Vector[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailingLeg(l))
  }

  def isRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Boolean = {
    getRideHailingTrip(chosenTrip).nonEmpty
  }

}

class RideHailingAgent(override val id: Id[RideHailingAgent], override val data: RideHailingAgentData, val beamServices: BeamServices)
  extends BeamAgent[RideHailingAgentData]
    with HasServices
    with DrivesVehicle[RideHailingAgentData] {
  override def logPrefix(): String = s"RideHailingAgent $id: "

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[RideHailingAgentData]) =>
      val passengerSchedule = PassengerSchedule()
      data.vehicleIdAndRef.ref ! BecomeDriver(tick, id, Some(passengerSchedule))
      goto(PersonAgent.Waiting) replying completed(triggerId, schedule[PassengerScheduleEmptyTrigger](tick,self))
  }

  chainedWhen(Waiting) {
    case Event(TriggerWithId(PassengerScheduleEmptyTrigger(tick), triggerId), info) =>
      val rideAvailable = ResourceIsAvailableNotification(self, info.data.vehicleIdAndRef.id, SpaceTime(info.data.location, tick.toLong))
      val managerFuture = (beamServices.rideHailingManager ? rideAvailable).mapTo[RideAvailableAck.type].map(result =>
        RegisterRideAvailableWrapper(triggerId)
      )
      managerFuture pipeTo self
      stay()
    case Event(RegisterRideAvailableWrapper(triggerId), info) =>
      beamServices.schedulerRef ! CompletionNotice(triggerId)
      stay()
    case Event(Finish, _) =>
      stop
  }

  chainedWhen(AnyState) {
    case Event(ModifyPassengerScheduleAck(Some(msgId)), _) =>
      stay
    case Event(BecomeDriverSuccessAck, _)  =>
      stay
  }


  //// BOILERPLATE /////

  when(Waiting) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message ${msg}"))
  }

  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message ${msg}"))
  }

  when(AnyState) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message ${msg}"))
  }

}


