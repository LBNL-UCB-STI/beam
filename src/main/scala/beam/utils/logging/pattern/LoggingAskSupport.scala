package beam.utils.logging.pattern

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import beam.sim.config.BeamConfig.Beam.Debug
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
  )(implicit context: ActorContext, timeout: Timeout, sender: ActorRef, debug: Debug): Future[Any] =
    actorRef.internalAsk(message, context, timeout, sender, debug)
}

final class LoggingAskableActorRef(val actorRef: ActorRef) extends AnyVal {

  def ask(message: Any)(implicit context: ActorContext, timeout: Timeout, sender: ActorRef, debug: Debug): Future[Any] =
    internalAsk(message, context, timeout, sender, debug)

  def ?(message: Any)(implicit context: ActorContext, timeout: Timeout, sender: ActorRef, debug: Debug): Future[Any] =
    internalAsk(message, context, timeout, sender, debug)

  private[pattern] def internalAsk(
    message: Any,
    context: ActorContext,
    timeout: Timeout,
    sender: ActorRef,
    debug: Debug,
  ): Future[Any] = {
    if (debug.messageLogging) {
      context.system.eventStream.publish(BeamMessage(sender, actorRef, message))
    }
    val futureResponse = akka.pattern
      .ask(actorRef)
      .ask(message)(timeout, sender)
    if (debug.messageLogging) {
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
