package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.ridehail.RideHailAgent.{
  Interrupt,
  ModifyPassengerSchedule,
  NotifyVehicleResourceIdleReply,
  Resume
}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.HasTriggerId
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
  val passengerSchedules: mutable.HashMap[Id[Vehicle], PassengerSchedule] = mutable.HashMap()
  val triggerIds: mutable.HashMap[Id[Vehicle], Option[Long]] = mutable.HashMap()

  def initiateMovementToParkingDepot(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule,
    tick: Int,
    triggerId: Long
  ): Unit = {
    log.debug("initiateMovementToParkingDepot - vehicle: " + vehicleId)

    passengerSchedules.put(vehicleId, passengerSchedule)

    rideHailManager.rideHailManagerHelper
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent
      .tell(
        Interrupt(
          RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId,
          tick,
          triggerId,
          vehicleId
        ),
        rideHailManagerActor
      )
  }

  def registerTrigger(vehicleId: Id[Vehicle], triggerId: Option[Long]): Option[Long] = {
    triggerIds.put(vehicleId, triggerId).flatten
  }

  def handleInterruptReply(
    vehicleId: Id[Vehicle],
    tick: Int,
    triggerId: Long
  ): Unit = {

    val rideHailAgent = rideHailManager.rideHailManagerHelper
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent.tell(
      ModifyPassengerSchedule(passengerSchedules(vehicleId), tick, triggerId),
      rideHailManagerActor
    )
    rideHailAgent.tell(Resume(triggerId), rideHailManagerActor)
  }

  def releaseTrigger(
    vehicleId: Id[Vehicle],
    triggersToSchedule: Seq[ScheduleTrigger] = Vector()
  ): Unit = {
    val rideHailAgent = rideHailManager.rideHailManagerHelper
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent ! NotifyVehicleResourceIdleReply(
      triggerIds(vehicleId).get,
      triggersToSchedule
    )
  }

}

case class ReleaseAgentTrigger(vehicleId: Id[Vehicle])

case class MoveOutOfServiceVehicleToDepotParking(
  passengerSchedule: PassengerSchedule,
  tick: Int,
  vehicleId: Id[Vehicle],
  triggerId: Long
) extends HasTriggerId
