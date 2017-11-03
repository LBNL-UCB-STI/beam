package beam.sim.monitoring

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import beam.agentsim.agents.BeamAgent
import beam.sim.monitoring.ErrorListener.WatchAgent

/**
  * @author sid.feygin
  *
  */
class ErrorListener(iter: Int) extends Actor with ActorLogging {
  private var nextCounter = 1
  private var erroredAgents: List[ActorRef] = Nil

  override def receive: Receive = {
    case BeamAgent.TerminatedPrematurelyEvent(agentRef) =>
      erroredAgents ::= agentRef
      if (erroredAgents.size >= nextCounter) {
        nextCounter *= 2
        log.error(s"\n\n\t****** Iteration: $iter\t||\tAgents gone to Error: ${erroredAgents.size} ********\n")
      }
  }
}

object ErrorListener {
  def props(iter: Int): Props = {
    Props(new ErrorListener(iter: Int))
  }
  case class WatchAgent(agent: ActorRef)
}
