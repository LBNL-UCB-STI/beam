package beam.utils.logging

import akka.actor.{Actor, ActorContext, ActorRef}
import akka.actor.Actor.Receive
import beam.utils.logging.LoggingMessageActor.messageLoggingEnabled
import beam.utils.logging.MessageLogger.BeamMessage
import com.typesafe.config.{Config, ConfigFactory}

/**
  * @author Dmitry Openkov
  */
trait LoggingMessageActor extends Actor {
  def loggedReceive: Receive

  val debugMessages: Boolean = messageLoggingEnabled(context.system.settings.config)

  override def receive: Receive =
    if (debugMessages) LoggingMessageReceive(loggedReceive)
    else loggedReceive

  def contextBecome(receive: Receive): Unit =
    if (debugMessages) context.become(LoggingMessageReceive(receive))
    else context.become(receive)
}

object LoggingMessageActor {
  def messageLoggingEnabled(config: Config): Boolean =
    if(config.hasPathOrNull("beam.debug.messageLogging")){
      config.getBoolean(("beam.debug.messageLogging"))
    } else false
}

trait LoggingMessagePublisher extends Actor {

  val debugMessages: Boolean = messageLoggingEnabled(context.system.settings.config)

  def publishMessage(msg: Any): Unit =
    if (debugMessages) {
      context.system.eventStream.publish(BeamMessage(context.sender(), context.self, msg))
    }

  def publishMessageFromTo(msg: Any, sender: ActorRef, receiver: ActorRef): Unit =
    if (debugMessages) {
      context.system.eventStream.publish(BeamMessage(sender, receiver, msg))
    }

}

class LoggingMessageReceive(r: Receive)(implicit context: ActorContext) extends Receive {

  def isDefinedAt(o: Any): Boolean = {
    val handled = r.isDefinedAt(o)
    val event = BeamMessage(context.sender(), context.self, o)
    context.system.eventStream.publish(event)
    handled
  }

  def apply(o: Any): Unit = r(o)
}

object LoggingMessageReceive {
  def apply(r: Receive)(implicit context: ActorContext): LoggingMessageReceive = new LoggingMessageReceive(r)
}
