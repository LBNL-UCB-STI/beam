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
          // Please, leave it like this because we already had a case when message wasn't written to the logs
          ex.printStackTrace()
          log.error(ex, "Critical actor encountered an unrecoverable exception, so killing the entire application.")
          Thread.sleep(30000) //This makes sure the log is written before crashing out
          sys.exit(123)
        }
    }
  }
}
