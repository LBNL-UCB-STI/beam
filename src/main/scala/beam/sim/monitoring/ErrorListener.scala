package beam.sim.monitoring

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.vehicles.AccessErrorCodes.DriverNotFoundError
import beam.agentsim.agents.vehicles.VehicleProtocol.RemovePassengerFromTrip
import beam.agentsim.agents.vehicles.{ReservationRequest, ReservationResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.Modes.BeamMode.TRANSIT

/**
  * @author sid.feygin
  *
  */
class ErrorListener() extends Actor with ActorLogging {
  private var nextCounter = 1
  private var terminatedPrematurelyEvents: List[BeamAgent.TerminatedPrematurelyEvent] = Nil

  override def receive: Receive = {
    case event @ BeamAgent.TerminatedPrematurelyEvent(_, _) =>
      terminatedPrematurelyEvents ::= event
      if (terminatedPrematurelyEvents.size >= nextCounter) {
        nextCounter *= 2
        log.error(
          s"\n\n\t****** Agents gone to Error: ${terminatedPrematurelyEvents.size} ********\n${formatErrorReasons()}"
        )
      }
    case d: DeadLetter =>
      d.message match {
        case m: ReservationRequest =>
          log.warning(
            s"Person ${d.sender} attempted to reserve ride with agent ${d.recipient} that was not found, message sent to dead letters."
          )
          d.sender ! ReservationResponse(m.requestId, Left(DriverNotFoundError), TRANSIT)
        case m: RemovePassengerFromTrip =>
        // Can be safely skipped
        case TriggerWithId(trigger, triggerId) =>
          log.warning(s"Trigger sent to dead letters ${trigger}")
          d.sender ! CompletionNotice(triggerId)
        //
        case _ =>
          log.error(s"ErrorListener: saw dead letter without knowing how to handle it: $d")
      }
    case _ =>
    ///
  }

  def formatErrorReasons(): String = {
    def hourOrMinus1(event: BeamAgent.TerminatedPrematurelyEvent) = -1
    val msgCounts = terminatedPrematurelyEvents
      .groupBy(
        event => event.reason.toString.substring(0, Math.min(event.reason.toString.length - 1, 65))
      )
      .mapValues(
        eventsPerReason =>
          eventsPerReason
            .groupBy(event => hourOrMinus1(event))
            .mapValues(eventsPerReasonPerHour => eventsPerReasonPerHour.size)
      )
    msgCounts
      .map {
        case (msg, cntByHour) =>
          val sortedCounts = cntByHour.toSeq.sortBy(_._1)
          s"$msg:\n\tHour\t${sortedCounts.map { case (hr, cnt) => hr.toString }.mkString("\t")}\n\tCnt \t${sortedCounts
            .map { case (hr, cnt)                              => cnt.toString }
            .mkString("\t")}"
      }
      .mkString("\n")
  }

}

object ErrorListener {

  def props(): Props = {
    Props(new ErrorListener())
  }
}
