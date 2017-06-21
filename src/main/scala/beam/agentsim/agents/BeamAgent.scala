package beam.agentsim.agents

import akka.actor.{FSM, LoggingFSM}
import akka.persistence.fsm.PersistentFSM.FSMState
import beam.agentsim.agents.BeamAgent._
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

  case class NoData() extends BeamAgentData

  case class BeamAgentInfo[T <: BeamAgentData](id: Id[_], implicit val data: T) extends Info

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
  *
  */
trait BeamAgent[T <: BeamAgentData] extends LoggingFSM[BeamAgentState, BeamAgentInfo[T]] {

  def id: Id[_]
  def data: T

  private val chainedStateFunctions = new HashMap[BeamAgentState, Set[StateFunction]] with MultiMap[BeamAgentState,StateFunction]
  final def chainedWhen(stateName: BeamAgentState)(stateFunction: StateFunction): Unit =
    chainedStateFunctions.addBinding(stateName,stateFunction)

  //TODO error check for duplicate completion notices
  def handleEvent(state: BeamAgentState, event: Event): State = {
    var theStateData = event.stateData
    var theEvent = event
    if(chainedStateFunctions.contains(state)) {
      var resultingBeamStates = List[BeamAgentState]()
      var resultingReplies = List[Any]()
      chainedStateFunctions(state).foreach { stateFunction =>
        if(stateFunction isDefinedAt (theEvent)){
          val fsmState: State = stateFunction(theEvent)
          theStateData = fsmState.stateData
          theEvent = Event(event.event,theStateData)
          resultingBeamStates = resultingBeamStates :+ fsmState.stateName
          resultingReplies = resultingReplies ::: fsmState.replies
        }
      }
      val newStates = for (result <- resultingBeamStates if result != Abstain) yield result
      if (newStates.size == 0 || !allStatesSame(newStates)){
        FSM.State(state, event.stateData)
      } else {
        FSM.State(newStates.head, theStateData, None, None, resultingReplies)
      }
    }else{
      FSM.State(state, event.stateData)
    }
  }
  def allStatesSame(theStates: List[BeamAgentState]): Boolean = {
    theStates.foldLeft(true)((result,stateToTest) => result && stateToTest == theStates.head)
  }

  startWith(Uninitialized, BeamAgentInfo[T](id, data))

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

