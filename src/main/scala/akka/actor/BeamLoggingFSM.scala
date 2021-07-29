/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import beam.agentsim.agents.HasTickAndTrigger
import beam.utils.logging.LoggingMessageActor.messageLoggingEnabled
import beam.utils.logging.MessageLogger.{BeamFSMMessage, BeamStateTransition}

trait BeamLoggingFSM[S, D] extends FSM[S, D] { this: Actor =>

  import FSM._

  def logDepth: Int = 0

  private[akka] override val debugEvent = context.system.settings.FsmDebugEvent

  val debugMessages: Boolean = messageLoggingEnabled(context.system.settings.config)

  private val events = new Array[Event](logDepth)
  private val states = new Array[AnyRef](logDepth)
  private var pos = 0
  private var full = false

  private def advance(): Unit = {
    val n = pos + 1
    if (n == logDepth) {
      full = true
      pos = 0
    } else {
      pos = n
    }
  }

  if (debugMessages) {
    onTransition { case x -> y =>
      val (tick, triggerId) = currentTickAndTriggerId
      val msg = BeamStateTransition(context.sender, context.self, x, y, tick, triggerId)
      context.system.eventStream.publish(msg)
    }
  }

  private[akka] abstract override def processEvent(event: Event, source: AnyRef): Unit = {
    if (debugEvent) {
      val srcstr = source match {
        case s: String               => s
        case Timer(name, _, _, _, _) => "timer " + name
        case a: ActorRef             => a.toString
        case _                       => "unknown"
      }
      log.debug("###FSM### {} processing {} from {} in state {}", this.self, event, srcstr, stateName)
    }

    if (logDepth > 0) {
      states(pos) = stateName.asInstanceOf[AnyRef]
      events(pos) = event
      advance()
    }

    if (debugMessages) {
      //storing incoming event into beam messages csv file
      val (tick, triggerId) = currentTickAndTriggerId
      val msg = BeamFSMMessage(context.sender(), context.self, event, tick, triggerId)
      context.system.eventStream.publish(msg)
    }

    val oldState = stateName
    super.processEvent(event, source)
    val newState = stateName

    if (debugEvent && oldState != newState)
      log.debug("###FSM-transition### actor:" + self.toString() + " transition: " + oldState + " -> " + newState)
  }

  private def currentTickAndTriggerId: (Int, Long) = {
    this match {
      case x: HasTickAndTrigger => (x.getCurrentTick.getOrElse(-1), x.getCurrentTriggerIdOrGenerate)
      case _                    => (-1, -1)
    }
  }

  /**
    * Retrieve current rolling log in oldest-first order. The log is filled with
    * each incoming event before processing by the user supplied state handler.
    * The log entries are lost when this actor is restarted.
    */
  protected def getLog: IndexedSeq[LogEntry[S, D]] = {
    val log =
      events.zip(states).filter(_._1 ne null).map(x => LogEntry(x._2.asInstanceOf[S], x._1.stateData, x._1.event))
    if (full) {
      IndexedSeq() ++ log.drop(pos) ++ log.take(pos)
    } else {
      IndexedSeq() ++ log
    }
  }

}
