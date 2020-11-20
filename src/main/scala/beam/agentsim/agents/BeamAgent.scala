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

  case class TerminatedPrematurelyEvent(actorRef: ActorRef, reason: FSM.Reason, tick: Option[Int])

  case object Uninitialized extends BeamAgentState

  case object Initialized extends BeamAgentState

  case object Finish

}

case class InitializeTrigger(tick: Int) extends Trigger

trait BeamAgent[T] extends LoggingFSM[BeamAgentState, T] with Stash with HasTickAndTrigger {

  val scheduler: ActorRef
  val eventsManager: EventsManager

  protected implicit val timeout: util.Timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)

  def id: Id[_]

  onTermination {
    case event @ StopEvent(reason @ (FSM.Failure(_) | FSM.Shutdown), currentState, _) =>
      reason match {
        case FSM.Shutdown =>
          log.error(
            "BeamAgent Got Shutdown. This means actorRef.stop() was called externally, e.g. by supervisor because of an exception. In state {}\n",
            currentState
          )
        case _ =>
      }
      log.error("State: {} Event: {}", currentState, event.toString)
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      context.system.eventStream.publish(TerminatedPrematurelyEvent(self, reason, _currentTick))
  }

  def logPrefix(): String

  def logInfo(msg: => String): Unit = {
    if (log.isInfoEnabled) {
      log.info("{} {}", getPrefix, msg)
    }
  }

  def logWarn(msg: => String): Unit = {
    if (log.isWarningEnabled) {
      log.warning("{} {}", getPrefix, msg)
    }
  }

  def logError(msg: => String): Unit = {
    if (log.isErrorEnabled) {
      log.error("{} {}", getPrefix, msg)
    }
  }

  def logDebug(msg: => String): Unit = {
    if (log.isDebugEnabled) {
      log.debug("{} {}", () => getPrefix, msg)
    }
  }

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

}
