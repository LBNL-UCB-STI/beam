package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{FSM, LoggingFSM}
import akka.persistence.fsm.PersistentFSM.FSMState
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.{Trigger, TriggerWithId}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event

import collection.mutable.{HashMap, MultiMap, Set}
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration


object BeamAgent {

  // states
  trait BeamAgentState extends FSMState

  case object Abstain extends BeamAgentState { override def identifier = "Abstain" }

  case object Uninitialized extends BeamAgentState { override def identifier = "Uninitialized" }

  case object Initialized extends BeamAgentState {
    override def identifier = "Initialized"
  }

  case object Finished extends BeamAgentState {
    override def identifier = "Finished"
  }

  case object Error extends BeamAgentState {
    override def identifier: String = s"Error!"
  }

  sealed trait Info

  trait BeamAgentData

  case class BeamAgentInfo[T <: BeamAgentData](id: Id[_],
                                               implicit val data: T,
                                               val triggerId: Option[Long] = None,
                                               val tick: Option[Double] = None) extends Info
  case class NoData() extends BeamAgentData

}

case class InitializeTrigger(tick: Double) extends Trigger
/**
  * MemoryEvents play a dual role. They not only act as persistence in Akka, but
  * also get piped to the MATSimEvent Handler.
  */
sealed trait MemoryEvent extends Event


/**
  * This FSM uses [[BeamAgentState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  */
trait BeamAgent[T <: BeamAgentData] extends LoggingFSM[BeamAgentState, BeamAgentInfo[T]] {

  def id: Id[_]
  def data: T
  protected implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  private val chainedStateFunctions = new mutable.HashMap[BeamAgentState, mutable.Set[StateFunction]] with mutable.MultiMap[BeamAgentState,StateFunction]
  final def chainedWhen(stateName: BeamAgentState)(stateFunction: StateFunction): Unit =
    chainedStateFunctions.addBinding(stateName,stateFunction)

  def handleEvent(state: BeamAgentState, event: Event): State = {
    var theStateData = event.stateData
    event match {
      case Event(TriggerWithId(trigger, triggerId), _) =>
        theStateData = theStateData.copy(triggerId = Some(triggerId), tick = Some(trigger.tick))
      case Event(_, _) =>
        // do nothing
    }
    var theEvent = event.copy(stateData = theStateData)
    if(chainedStateFunctions.contains(state)) {
      var resultingBeamStates = List[BeamAgentState]()
      var resultingReplies = List[Any]()
      chainedStateFunctions(state).foreach { stateFunction =>
        if(stateFunction isDefinedAt (theEvent)){
          val fsmState: State = stateFunction(theEvent)
          theStateData = fsmState.stateData.copy(triggerId = theStateData.triggerId, tick = theStateData.tick)
          theEvent = Event(event.event,theStateData)
          resultingBeamStates = resultingBeamStates :+ fsmState.stateName
          resultingReplies = resultingReplies ::: fsmState.replies
        }
      }
      val newStates = for (result <- resultingBeamStates if result != Abstain) yield result
      if (newStates.isEmpty || !allStatesSame(newStates)){
        throw new RuntimeException(s"Chained when blocks did not achieve consensus on state to transition " +
          s" to for BeamAgent ${stateData.id}, newStates: $newStates, theEvent=$theEvent ,")
      } else {
        val numCompletionNotices = resultingReplies.count(_.isInstanceOf[CompletionNotice])
        if(numCompletionNotices>1){
          throw new RuntimeException(s"Chained when blocks attempted to reply with multiple CompletionNotices for BeamAgent ${stateData.id}")
        }else if(numCompletionNotices == 1){
          theStateData = theStateData.copy(triggerId = None)
        }
        FSM.State(newStates.head, theStateData, None, None, resultingReplies)
      }
    }else{
      FSM.State(state, event.stateData)
    }
  }
  def numCompletionNotices(theReplies: List[Any]): Int = {
    theReplies.count(_.isInstanceOf[CompletionNotice])
  }
  def allStatesSame(theStates: List[BeamAgentState]): Boolean = {
    theStates.forall(stateToTest => stateToTest == theStates.head)
  }

  startWith(Uninitialized, BeamAgentInfo[T](id, data))

  when(Uninitialized){
    case ev @ Event(_,_) =>
      handleEvent(stateName, ev)
  }
  when(Initialized){
    case ev @ Event(_,_) =>
      handleEvent(stateName, ev)
  }

  when(Finished) {
    case Event(StopEvent, _) =>
      goto(Uninitialized)
  }

  when(Error) {
    case Event(StopEvent, _) =>
      stop()
  }

  whenUnhandled {
    case Event(any, data) =>
      log.error(s"Unhandled event: $id $any $data")
      stay()
  }


}

