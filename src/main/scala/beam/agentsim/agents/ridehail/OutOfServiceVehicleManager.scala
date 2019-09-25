package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.ridehail.RideHailAgent.{
  Interrupt,
  ModifyPassengerSchedule,
  NotifyVehicleResourceIdleReply,
  Resume
}
import beam.agentsim.agents.vehicles.{BeamVehicleId, PassengerSchedule}
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.utils.DebugLib
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

// TODO: remove params not needed!
class OutOfServiceVehicleManager(
  val log: LoggingAdapter,
  val rideHailManagerActor: ActorRef,
  val rideHailManager: RideHailManager
) {

  // TODO: refactor the following two later, e.g. into class
  val passengerSchedules: mutable.HashMap[BeamVehicleId, PassengerSchedule] = mutable.HashMap()
  val triggerIds: mutable.HashMap[BeamVehicleId, Option[Long]] = mutable.HashMap()

  def initiateMovementToParkingDepot(
    vehicleId: BeamVehicleId,
    passengerSchedule: PassengerSchedule,
    tick: Int
  ): Unit = {
    log.debug("initiateMovementToParkingDepot - vehicle: " + vehicleId)

    passengerSchedules.put(vehicleId, passengerSchedule)

    rideHailManager.vehicleManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent
      .tell(
        Interrupt(RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId, tick),
        rideHailManagerActor
      )
  }

  def registerTrigger(vehicleId: BeamVehicleId, triggerId: Option[Long]): Option[Long] = {
    triggerIds.put(vehicleId, triggerId).flatten
  }

  def handleInterruptReply(
    vehicleId: BeamVehicleId,
    tick: Int
  ): Unit = {

    val rideHailAgent = rideHailManager.vehicleManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent.tell(
      ModifyPassengerSchedule(passengerSchedules(vehicleId), tick),
      rideHailManagerActor
    )
    rideHailAgent.tell(Resume, rideHailManagerActor)
  }

  def releaseTrigger(
    vehicleId: BeamVehicleId,
    triggersToSchedule: Seq[ScheduleTrigger] = Vector()
  ): Unit = {
    val rideHailAgent = rideHailManager.vehicleManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent ! NotifyVehicleResourceIdleReply(
      triggerIds(vehicleId),
      triggersToSchedule
    )
  }

}

case class ReleaseAgentTrigger(vehicleId: BeamVehicleId)

case class MoveOutOfServiceVehicleToDepotParking(
  passengerSchedule: PassengerSchedule,
  tick: Int,
  vehicleId: BeamVehicleId,
  stall: ParkingStall
)
