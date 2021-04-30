package beam.utils.logging.pattern

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import beam.utils.logging.MessageLogger.BeamMessage

import scala.concurrent.Future
import scala.util.Success

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
    val futureResponse = akka.pattern
      .ask(actorRef)
      .ask(message)(timeout, sender)
    if (context.system.settings.AddLoggingReceive) {
      import scala.concurrent.ExecutionContext.Implicits.global
      futureResponse.andThen {
        case Success(msg) =>
          //hopefully when ask pattern is used the response comes from the destination actor
          //so we just swap sender and actor being asked
          context.system.eventStream.publish(BeamMessage(actorRef, sender, msg))
      }
    } else futureResponse

  }

}
