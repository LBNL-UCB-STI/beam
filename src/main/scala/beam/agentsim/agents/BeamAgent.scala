package beam.agentsim.agents

import java.util.{Set, TreeSet}
import java.util.concurrent.TimeUnit

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.actor.{ActorRef, FSM, LoggingFSM}
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

  case object AnyState extends BeamAgentState {
    override def identifier = "AnyState"
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
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Double] = None
  protected var listener: Option[ActorRef] = None

  private val chainedStateFunctions = new mutable.HashMap[BeamAgentState, mutable.Set[StateFunction]] with mutable.MultiMap[BeamAgentState,StateFunction]
  final def chainedWhen(stateName: BeamAgentState)(stateFunction: StateFunction): Unit = {
    chainedStateFunctions.addBinding(stateName,stateFunction)
  }

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
      if (!allStatesSame(newStates)){
        throw new RuntimeException(s"Chained when blocks did not achieve consensus on state to transition " +
          s" to for BeamAgent ${stateData.id}, newStates: $newStates, theEvent=$theEvent ,")
      } else if(newStates.isEmpty && state == AnyState){

        logError(s"Did not handle the event=$event")
        FSM.State(Error, event.stateData)
      } else if(newStates.isEmpty){
        handleEvent(AnyState, event)
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
    case ev @ Event(SubscribeTransitionCallBack(actorRef),_)=>
      listener = Some(actorRef)
      // send current state back as reference point
      actorRef ! CurrentState(self, this.stateName)
      stay()

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
    case ev@Event(_, _) =>
      val result = handleEvent(AnyState, ev)
      if(result.stateName == AnyState){
        logWarn(s"Unrecognized event ${ev.event}")
        stay()
      }else{
        result
      }
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }

  /*
   * Helper methods
   */
  def holdTickAndTriggerId(tick: Double, triggerId: Long) = {
    if(_currentTriggerId.isDefined || _currentTick.isDefined)
      throw new IllegalStateException(s"Expected both _currentTick and _currentTriggerId to be 'None' but found ${_currentTick} and ${_currentTriggerId} instead, respectively.")

    _currentTick = Some(tick)
    _currentTriggerId = Some(triggerId)
  }
  def releaseTickAndTriggerId(): (Double, Long) = {
    val theTuple = (_currentTick.get, _currentTriggerId.get)
    _currentTick = None
    _currentTriggerId = None
    theTuple
  }
  def logPrefix(): String
  def fullLogPrefix(): String = {
    val tickStr = _currentTick match {
      case Some(theTick) =>
        s"Tick:${theTick.toString} "
      case None =>
        ""
    }
    val triggerStr = _currentTriggerId match {
      case Some(theTriggerId) =>
        s"TriggId:${theTriggerId.toString} "
      case None =>
        ""
    }
    s"${tickStr}${triggerStr}State:${stateName} ${logPrefix()}"
  }
  def logInfo(msg: String): Unit = {
    log.info(s"${fullLogPrefix}$msg")
  }
  def logWarn(msg: String): Unit = {
    log.warning(s"${fullLogPrefix}$msg")
  }
  def logError(msg: String): Unit = {
    log.error(s"${fullLogPrefix}$msg")
  }
  def logDebug(msg: String): Unit = {
    log.debug(s"${fullLogPrefix}$msg")
  }

}

