package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, FSM, LoggingFSM, Stash}
import akka.util
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.scheduler.Trigger
import org.matsim.api.core.v01.Id
import org.matsim.core.api.experimental.events.EventsManager

object BeamAgent {

  // states
  trait BeamAgentState

  case object Uninitialized extends BeamAgentState

  case object Initialized extends BeamAgentState

  case object Finish

  case class TerminatedPrematurelyEvent(actorRef: ActorRef, reason: FSM.Reason, tick: Option[Int])

}

case class InitializeTrigger(tick: Int) extends Trigger

trait BeamAgent[T] extends LoggingFSM[BeamAgentState, T] with Stash {

  val scheduler: ActorRef
  val eventsManager: EventsManager

  def id: Id[_]

  protected implicit val timeout: util.Timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Int] = None

  onTermination {
    case event @ StopEvent(reason @ (FSM.Failure(_) | FSM.Shutdown), currentState, _) =>
      reason match {
        case FSM.Shutdown =>
          log.error(
            "Got Shutdown. This means actorRef.stop() was called externally, e.g. by supervisor because of an exception.\n"
          )
        case _ =>
      }
      log.error("State: {} Event: {}", currentState, event.toString)
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      context.system.eventStream.publish(TerminatedPrematurelyEvent(self, reason, _currentTick))
  }

  def holdTickAndTriggerId(tick: Int, triggerId: Long): Unit = {
    if (_currentTriggerId.isDefined || _currentTick.isDefined)
      throw new IllegalStateException(
        s"Expected both _currentTick and _currentTriggerId to be 'None' but found ${_currentTick} and ${_currentTriggerId} instead, respectively."
      )

    _currentTick = Some(tick)
    _currentTriggerId = Some(triggerId)
  }

  def releaseTickAndTriggerId(): (Int, Long) = {
    val theTuple = (_currentTick.get, _currentTriggerId.get)
    _currentTick = None
    _currentTriggerId = None
    theTuple
  }

  def logPrefix(): String

  def getPrefix: String = {
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
    s"$tickStr${triggerStr}State:$stateName ${logPrefix()}"
  }

  def logInfo(msg: => String): Unit = {
    log.info("{} {}", getPrefix, msg)
  }

  def logWarn(msg: => String): Unit = {
    log.warning("{} {}", getPrefix, msg)
  }

  def logError(msg: => String): Unit = {
    log.error("{} {}", getPrefix, msg)
  }

  def logDebug(msg: => String): Unit = {
    log.debug("{} {}", getPrefix, msg)
  }

}
