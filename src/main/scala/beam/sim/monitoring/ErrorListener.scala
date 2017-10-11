package beam.sim.monitoring

import java.util.concurrent.atomic.AtomicLong

import akka.actor.FSM.{CurrentState, Transition}
import akka.actor.{Actor, ActorLogging, ActorRef, FSM, Props}
import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.{BeamAgentData, BeamAgentInfo, BeamAgentState}
import beam.agentsim.agents.PersonAgent.PersonData
import beam.sim.monitoring.ErrorListener.{ErrorReasonResponse, RequestErrorReason}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * @author sid.feygin
  *
  */
class ErrorListener(iter: Int) extends Actor with ActorLogging {
  private var counter: AtomicLong = new AtomicLong(0)
  private val nextCounter:AtomicLong = new AtomicLong(1)
  private var erroredAgents: mutable.Set[ActorRef] = mutable.Set[ActorRef]()
  private var errorReasons: mutable.Map[String,mutable.TreeMap[Int,Int]] = mutable.Map[String,mutable.TreeMap[Int,Int]]()

  override def receive: Receive = {
    case CurrentState(agentRef: ActorRef, BeamAgent.Uninitialized) =>
      log.debug(s"Monitoring ${agentRef.path}")
    case Transition(agentRef: ActorRef, _, BeamAgent.Error) =>
      if(!erroredAgents.contains(agentRef)) {
        agentRef ! RequestErrorReason
        erroredAgents += agentRef
        val i = this.counter.incrementAndGet
        val n = this.nextCounter.get
        if (i >= n) if (this.nextCounter.compareAndSet(n, n * 2)) {
          log.error(s"\n\n\t****** Iteration: $iter\t||\tAgents gone to Error: ${n.toString} ********\n${formatErrorReasons()}")
        }
      }
    case Transition(agentRef: ActorRef,_,_)=>
      //Do nothing
    case ErrorReasonResponse(reasonOpt,tick,errorData) =>
      // Logging event-> State change data
      val theErrorData = errorData.zipWithIndex.map{case(le,idx)=>s"$idx) ${le.event}"}.mkString("\n\t")
      log.error(theErrorData)

      var theMessage = reasonOpt match {
        case Some(msg) =>
          msg
        case None =>
          "No reason provided."
      }
      val hourOfSim = tick match {
        case Some(tickDouble) =>
          (tickDouble / 3600.0).toInt
        case None =>
          -1
      }
      if(errorReasons.contains(theMessage)){
        if(errorReasons.get(theMessage).get.contains(hourOfSim)) {
          errorReasons.get(theMessage).get.put(hourOfSim,errorReasons.get(theMessage).get(hourOfSim) + 1)
        }else{
          errorReasons.get(theMessage).get.put(hourOfSim,1)
        }
      }else{
        errorReasons.put(theMessage,mutable.TreeMap[Int,Int]())
        errorReasons.get(theMessage).get.put(hourOfSim,1)
      }
  }

  def formatErrorReasons(): String = {
    errorReasons.map{case(msg, cntByHour) => s"${msg}:\n\tHour\t${cntByHour.map{ case(hr, cnt) => hr.toString}.mkString("\t")}\n\tCnt \t${cntByHour.map{ case(hr, cnt) => cnt.toString}.mkString("\t")}"}.mkString("\n")
  }

}

object ErrorListener {
  def props(iter: Int): Props = {
    Props(new ErrorListener(iter: Int))
  }
  case object RequestErrorReason
  case class ErrorReasonResponse[T<:BeamAgentData](reason: Option[String], tick: Option[Double], errorData: Seq[FSM.LogEntry[BeamAgentState,BeamAgentInfo[T]]])
}
