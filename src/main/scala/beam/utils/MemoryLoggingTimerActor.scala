package beam.utils

import akka.actor.{Actor, ActorLogging}
import akka.actor.ActorSystem
import scala.concurrent.duration._


class MemoryLoggingTimerActor extends Actor with ActorLogging{
  def receive = {
    case Tick ⇒
      log.info(DebugLib.gcAndGetMemoryLogMessage("Memory use after GC: "))
  }




}

case object Tick

