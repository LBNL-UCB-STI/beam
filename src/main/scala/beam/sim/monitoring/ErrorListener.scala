package beam.sim.monitoring

import akka.actor.{Actor, ActorLogging, DeadLetter, Props}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.EndRefuelSessionTrigger
import beam.agentsim.agents.ridehail.RideHailAgent.{Interrupt, InterruptedWhileOffline}
import beam.agentsim.agents.vehicles.AccessErrorCodes.DriverNotFoundError
import beam.agentsim.agents.vehicles.VehicleProtocol.RemovePassengerFromTrip
import beam.agentsim.agents.vehicles.{ReservationRequest, ReservationResponse}
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, RoutingRequest, WorkAvailable}
import beam.router.Modes.BeamMode.TRANSIT

/**
  * @author sid.feygin
  *
  */
class ErrorListener() extends Actor with ActorLogging {
  private var nextCounter = 1
  private var terminatedPrematurelyEvents: List[BeamAgent.TerminatedPrematurelyEvent] = Nil

  override def receive: Receive = {
    case event @ BeamAgent.TerminatedPrematurelyEvent(_, _, _) =>
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
          d.sender ! ReservationResponse(Left(DriverNotFoundError))
        case _: RemovePassengerFromTrip =>
        // Can be safely skipped
        case TriggerWithId(EndRefuelSessionTrigger(_, _, _, _), triggerId) =>
          // Can be safely skipped, happens when a person ends the day before the charging session is over
          d.sender ! CompletionNotice(triggerId)
        case TriggerWithId(trigger, triggerId) =>
          log.warning("Trigger id {} sent to dead letters: {}", triggerId, trigger)
          d.sender ! CompletionNotice(triggerId)
        // Allow RHM to continue
        case interrupt: Interrupt =>
          log.error(s"Received ${interrupt} from ${d.sender}")
          d.sender ! InterruptedWhileOffline
        case m: RoutingRequest =>
          log.debug(
            "Retrying {} via {} tell {} using {}",
            m.requestId,
            d.recipient,
            d.message,
            d.sender
          )
          d.recipient.tell(d.message, d.sender)
        case m: EmbodyWithCurrentTravelTime =>
          log.debug("Retrying {} via {} tell {} using {}", m.requestId, d.recipient, d.message, d.sender)
          d.recipient.tell(d.message, d.sender)
        case WorkAvailable => //Do not retry GimmeWork - resiliency is built in
        case _ =>
          log.error(s"ErrorListener: saw dead letter without knowing how to handle it: $d")
      }
    case _ =>
    ///
  }

  def formatErrorReasons(): String = {
    def hourOrMinus1(event: BeamAgent.TerminatedPrematurelyEvent) =
      event.tick.map(tick => Math.round(tick / 3600.0).toInt).getOrElse(-1)

    val msgCounts = terminatedPrematurelyEvents
      .groupBy(event => "ALL")
      .mapValues(
        eventsPerReason =>
          eventsPerReason
            .groupBy(event => hourOrMinus1(event))
            .mapValues(eventsPerReasonPerHour => eventsPerReasonPerHour.size)
      )
    msgCounts
      .map {
        case (msg, cntByHour) =>
          val sortedCounts = cntByHour.toSeq.sortBy { case (hr, cnt) => hr }
          s"$msg:\n\tHour\t${sortedCounts.map { case (hr, _) => hr.toString }.mkString("\t")}\n\tCnt \t${sortedCounts
            .map { case (_, cnt)                             => cnt.toString }
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
