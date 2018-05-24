package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.InterruptMessageStatus.InterruptMessageStatus
import beam.agentsim.agents.rideHail.InterruptOrigin.InterruptOrigin
import beam.agentsim.agents.rideHail.RideHailingAgent.Interrupt
import beam.agentsim.agents.vehicles.PassengerSchedule
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

class RideHailModifyPassengerScheduleManager {

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
