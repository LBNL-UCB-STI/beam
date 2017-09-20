package beam.sim.monitoring

import java.util.concurrent.atomic.AtomicLong

import akka.actor.FSM.{CurrentState, Transition}
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.agents.BeamAgent

import scala.collection.mutable

/**
  * @author sid.feygin
  *
  */
class ErrorListener(iter: Int) extends Actor with ActorLogging {
  private var counter: AtomicLong = new AtomicLong(0)
  private val nextCounter:AtomicLong = new AtomicLong(1)
  private var erroredAgents: mutable.Set[ActorRef] = mutable.Set[ActorRef]()

  override def receive: Receive = {
    case CurrentState(agentRef: ActorRef, BeamAgent.Uninitialized) =>
      log.debug(s"Monitoring ${agentRef.path}")
    case Transition(agentRef: ActorRef, _, BeamAgent.Error) =>
      erroredAgents += agentRef
      val i = this.counter.incrementAndGet
      val n = this.nextCounter.get
      if (i >= n) if (this.nextCounter.compareAndSet(n, n * 2))
        {
          log.error(s"\n\n\t****** Iteration: $iter\t||\tAgents gone to Error: ${n.toString} ********\n")
        }
    case Transition(agentRef: ActorRef,_,_)=>
      //Do nothing
  }


}

object ErrorListener {
  def props(iter: Int): Props = {
    Props(new ErrorListener(iter: Int))
  }
}
