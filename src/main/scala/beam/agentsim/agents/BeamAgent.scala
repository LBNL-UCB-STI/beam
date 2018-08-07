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

  case class TerminatedPrematurelyEvent(actorRef: ActorRef, reason: FSM.Reason)

}

case class InitializeTrigger(tick: Double) extends Trigger

trait BeamAgent[T] extends LoggingFSM[BeamAgentState, T] with Stash {

  val scheduler: ActorRef
  val eventsManager: EventsManager

  def id: Id[_]

  protected implicit val timeout: util.Timeout = akka.util.Timeout(5000, TimeUnit.SECONDS)
  protected var _currentTriggerId: Option[Long] = None
  protected var _currentTick: Option[Double] = None

  onTermination {
    case event @ StopEvent(reason @ (FSM.Failure(_) | FSM.Shutdown), _, _) =>
      reason match {
        case FSM.Shutdown =>
          log.error(
            "Got Shutdown. This means actorRef.stop() was called externally, e.g. by supervisor because of an exception.\n"
          )
        case _ =>
      }
      log.error(event.toString)
      log.error("Events leading up to this point:\n\t" + getLog.mkString("\n\t"))
      context.system.eventStream.publish(TerminatedPrematurelyEvent(self, reason))
  }

  def holdTickAndTriggerId(tick: Double, triggerId: Long): Unit = {
    if (_currentTriggerId.isDefined || _currentTick.isDefined)
      throw new IllegalStateException(
        s"Expected both _currentTick and _currentTriggerId to be 'None' but found ${_currentTick} and ${_currentTriggerId} instead, respectively."
      )

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
