package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import beam.agentsim.agents.ridehail.RideHailAgent.{Interrupt, ModifyPassengerSchedule, NotifyVehicleResourceIdleReply, Resume}
import beam.agentsim.agents.ridehail.{RideHailManager, RideHailModifyPassengerScheduleManager}
import beam.agentsim.agents.vehicles.PassengerSchedule
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.sim.config.BeamConfig
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
  val passengerSchedules: mutable.HashMap[Id[Vehicle], PassengerSchedule] = mutable.HashMap()
  val triggerIds: mutable.HashMap[Id[Vehicle], Option[Long]] = mutable.HashMap()

  def initiateMovementToParkingDepot(
    vehicleId: Id[Vehicle],
    passengerSchedule: PassengerSchedule,
    tick: Double
  ): Unit = {
    log.debug("initiateMovementToParkingDepot - vehicle: " + vehicleId)

    passengerSchedules.put(vehicleId, passengerSchedule)

    rideHailManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent
      .tell(
        Interrupt(RideHailModifyPassengerScheduleManager.nextRideHailAgentInterruptId, tick),
        rideHailManagerActor
      )
  }

  def registerTrigger(vehicleId: Id[Vehicle], triggerId: Option[Long]) = {
    triggerIds.put(vehicleId, triggerId)
  }

  def handleInterrupt(
    vehicleId: Id[Vehicle],
  ): Unit = {

    val rideHailAgent = rideHailManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent.tell(
      ModifyPassengerSchedule(passengerSchedules.get(vehicleId).get),
      rideHailManagerActor
    )
    rideHailAgent.tell(Resume(), rideHailManagerActor)
    DebugLib.emptyFunctionForSettingBreakPoint()
  }

  def releaseTrigger(
    vehicleId: Id[Vehicle],
    triggersToSchedule: Seq[ScheduleTrigger] = Vector[ScheduleTrigger]()
  ): Unit = {
    val rideHailAgent = rideHailManager
      .getRideHailAgentLocation(vehicleId)
      .rideHailAgent

    rideHailAgent ! NotifyVehicleResourceIdleReply(
      triggerIds.get(vehicleId).get,
      triggersToSchedule
    )
  }

}

case class ReleaseAgentTrigger(vehicleId: Id[Vehicle])

case class MoveOutOfServiceVehicleToDepotParking(
  passengerSchedule: PassengerSchedule,
  tick: Double,
  vehicleId: Id[Vehicle],
  stall: ParkingStall
)
