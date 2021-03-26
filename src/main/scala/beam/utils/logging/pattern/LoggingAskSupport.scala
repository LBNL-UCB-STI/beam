package beam.utils.logging.pattern

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import beam.utils.logging.MessageLogger.BeamMessage

import scala.concurrent.Future

/**
  * @author Dmitry Openkov
  */
trait LoggingAskSupport {
  import scala.language.implicitConversions
  implicit def ask(actorRef: ActorRef): LoggingAskableActorRef = new LoggingAskableActorRef(actorRef)

  def ask(
    actorRef: ActorRef,
    message: Any
  )(implicit context: ActorContext, timeout: Timeout, sender: ActorRef): Future[Any] =
    actorRef.internalAsk(message, context, timeout, sender)
}

final class LoggingAskableActorRef(val actorRef: ActorRef) extends AnyVal {

  def ask(message: Any)(implicit context: ActorContext, timeout: Timeout, sender: ActorRef): Future[Any] =
    internalAsk(message, context, timeout, sender)

  def ?(message: Any)(implicit context: ActorContext, timeout: Timeout, sender: ActorRef): Future[Any] =
    internalAsk(message, context, timeout, sender)

  private[pattern] def internalAsk(
    message: Any,
    context: ActorContext,
    timeout: Timeout,
    sender: ActorRef
  ): Future[Any] = {
    if (context.system.settings.AddLoggingReceive) {
      context.system.eventStream.publish(BeamMessage(sender, actorRef, message))
    }
    akka.pattern
      .ask(actorRef)
      .ask(message)(timeout, sender)
  }

}
