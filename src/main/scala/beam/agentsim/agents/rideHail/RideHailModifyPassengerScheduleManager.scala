package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.InterruptMessageStatus.InterruptMessageStatus
import beam.agentsim.agents.rideHail.InterruptOrigin.InterruptOrigin
import beam.agentsim.agents.rideHail.RideHailingAgent.Interrupt
import beam.agentsim.agents.vehicles.PassengerSchedule
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import scala.collection.{concurrent, mutable}

class RideHailModifyPassengerScheduleManager() {

  val modifyPassengerScheduleStatus = mutable.Map[Id[Interrupt], RideHailModifyPassengerScheduleStatus]()
  val vehicleInterruptIds = mutable.Map[Id[Vehicle], mutable.Set[RideHailModifyPassengerScheduleStatus]]()

  def add(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    modifyPassengerScheduleStatus.put(rideHailModifyPassengerScheduleStatus.interruptId, rideHailModifyPassengerScheduleStatus)
    addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus)
  }

  private def addToVehicleInterruptIds(rideHailModifyPassengerScheduleStatus: RideHailModifyPassengerScheduleStatus): Unit = {
    if (!vehicleInterruptIds.contains(rideHailModifyPassengerScheduleStatus.vehicleId)) {
      vehicleInterruptIds.put(rideHailModifyPassengerScheduleStatus.vehicleId, mutable.Set[RideHailModifyPassengerScheduleStatus]())
    }
    var set = vehicleInterruptIds.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
    set.add(rideHailModifyPassengerScheduleStatus)
  }

  def getWithInterruptId(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    modifyPassengerScheduleStatus.get(interruptId)
  }

  def getWithVehicleIds(vehicleId: Id[Vehicle]): Set[RideHailModifyPassengerScheduleStatus] = {
    collection.immutable.Set(vehicleInterruptIds.get(vehicleId).get.toVector: _*)
  }

  def remove(interruptId: Id[Interrupt]): Option[RideHailModifyPassengerScheduleStatus] = {
    modifyPassengerScheduleStatus.remove(interruptId) match {
      case Some(rideHailModifyPassengerScheduleStatus) =>
        val set=vehicleInterruptIds.get(rideHailModifyPassengerScheduleStatus.vehicleId).get
        set.remove(rideHailModifyPassengerScheduleStatus)
        Some(rideHailModifyPassengerScheduleStatus)
      case None =>
        None
    }
  }

}

object InterruptMessageStatus extends Enumeration {
  type InterruptMessageStatus = Value
  val UNDEFINED, INTERRUPT_SENT, MODIFY_PASSENGER_SCHEDULE_SENT = Value
}

object InterruptOrigin extends Enumeration {
  type InterruptOrigin = Value
  val RESERVATION, REPOSITION = Value
}

class RideHailModifyPassengerScheduleStatus(val interruptId: Id[Interrupt], val vehicleId: Id[Vehicle], val passengerSchedule: PassengerSchedule, val interruptOrigin: InterruptOrigin, var status: InterruptMessageStatus)
