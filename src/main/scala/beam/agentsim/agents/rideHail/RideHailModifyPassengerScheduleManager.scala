package beam.agentsim.agents.rideHail

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