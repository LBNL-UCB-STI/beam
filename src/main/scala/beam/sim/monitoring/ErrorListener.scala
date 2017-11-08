package beam.sim.monitoring

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.agents.BeamAgent

/**
  * @author sid.feygin
  *
  */
class ErrorListener(iter: Int) extends Actor with ActorLogging {
  private var nextCounter = 1
  private var terminatedPrematurelyEvents: List[BeamAgent.TerminatedPrematurelyEvent] = Nil

  override def receive: Receive = {
    case event@BeamAgent.TerminatedPrematurelyEvent(agentRef, reason, maybeTick) =>
      terminatedPrematurelyEvents ::= event
      if (terminatedPrematurelyEvents.size >= nextCounter) {
        nextCounter *= 2
        log.error(s"\n\n\t****** Iteration: $iter\t||\tAgents gone to Error: ${terminatedPrematurelyEvents.size} ********\n${formatErrorReasons()}")
      }
  }

  def formatErrorReasons(): String = {
    def hourOrMinus1(event: BeamAgent.TerminatedPrematurelyEvent) = event.tick.map(_ / 3600.0).getOrElse(-1.0).toInt
    terminatedPrematurelyEvents
      .groupBy( event => event.reason )
      .mapValues( eventsPerReason =>
        eventsPerReason
          .groupBy(event => hourOrMinus1(event))
          .mapValues(eventsPerReasonPerHour => eventsPerReasonPerHour.size))
      .map{case(msg, cntByHour) => s"$msg:\n\tHour\t${cntByHour.map{ case(hr, cnt) => hr.toString}.mkString("\t")}\n\tCnt \t${cntByHour.map{ case(hr, cnt) => cnt.toString}.mkString("\t")}"}.mkString("\n")
  }

}

object ErrorListener {
  def props(iter: Int): Props = {
    Props(new ErrorListener(iter: Int))
  }
}
