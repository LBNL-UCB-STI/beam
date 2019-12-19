package beam.utils

import akka.actor.{Actor, ActorLogging}
import akka.actor.Actor.Receive

trait CriticalActor extends Actor with ActorLogging {
  val isCriticalExceptionHandler: PartialFunction[Throwable, Boolean] = { case _: Throwable => true }

  override def aroundReceive(r: Receive, msg: Any): Unit = {
    try {
      super.aroundReceive(r, msg)
    } catch {
      case ex: Throwable =>
        if (isCriticalExceptionHandler(ex)) {
          log.error(ex, "Critical actor encountered an unrecoverable exception, so killing the entire application.")
          Thread.sleep(1000) //This makes sure the log is written before crashing out
          sys.exit(123)
        }
    }
  }
}
