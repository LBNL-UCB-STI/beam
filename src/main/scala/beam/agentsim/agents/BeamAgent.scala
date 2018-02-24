package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, FSM, LoggingFSM}
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.agentsim.scheduler.Trigger
import org.matsim.api.core.v01.Id
import org.matsim.core.api.experimental.events.EventsManager


object BeamAgent {

  // states
  trait BeamAgentState

  case object Abstain extends BeamAgentState

  case object Uninitialized extends BeamAgentState

  case object Initialized extends BeamAgentState

  case object AnyState extends BeamAgentState

  case object Finished extends BeamAgentState

  case object Error extends BeamAgentState

  sealed trait Info

  trait BeamAgentData

  case class BeamAgentInfo[+T <: BeamAgentData](id: Id[_],
                                                data: T,
                                                triggerId: Option[Long] = None,
                                                tick: Option[Double] = None,
                                                triggersToSchedule: Vector[ScheduleTrigger] = Vector.empty,
                                                errorReason: Option[String] = None) extends Info

  case class NoData() extends BeamAgentData

  case object Finish

  case class TerminatedPrematurelyEvent(actorRef: ActorRef, reason: FSM.Reason, tick: Option[Double])

}

case class InitializeTrigger(tick: Double) extends Trigger


/**
  * This FSM uses [[BeamAgentState]] and [[BeamAgentInfo]] to define the state and
  * state data types.
  */
trait BeamAgent[T <: BeamAgentData] extends LoggingFSM[BeamAgentState, BeamAgentInfo[T]]  {

  val scheduler: ActorRef
  val eventsManager: EventsManager

  def id: Id[_]

  def data: T
  protected implicit val timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Double] = None

  startWith(Uninitialized, BeamAgentInfo[T](id, data))

  onTermination {
    case event@StopEvent(reason@(FSM.Failure(_) | FSM.Shutdown), _, stateData) =>
      reason match {
        case FSM.Shutdown =>
          log.error("Got Shutdown. This means actorRef.stop() was called externally, e.g. by supervisor because of an exception.\n")
        case _ =>
      }
      log.error(event.toString)
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      context.system.eventStream.publish(TerminatedPrematurelyEvent(self, reason, stateData.tick))
  }

  def holdTickAndTriggerId(tick: Double, triggerId: Long) = {
    if (_currentTriggerId.isDefined || _currentTick.isDefined)
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

  def logWithFullPrefix(msg: String): String = {
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
    s"$tickStr${triggerStr}State:$stateName ${logPrefix()}$msg"
  }

  def logInfo(msg: String): Unit = {
    log.info(s"${logWithFullPrefix(msg)}")
  }

  def logWarn(msg: String): Unit = {
    log.warning(s"${logWithFullPrefix(msg)}")
  }

  def logError(msg: String): Unit = {
    log.error(s"${logWithFullPrefix(msg)}")
  }

  def logDebug(msg: String): Unit = {
    log.debug(s"${logWithFullPrefix(msg)}")
  }

}

