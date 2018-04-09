package beam.utils

import akka.actor.{Actor, ActorLogging}
import akka.actor.ActorSystem
import scala.concurrent.duration._


class MemoryLoggingTimerActor extends Actor with ActorLogging{
  def receive = {
    case Tick ⇒
      Runtime.getRuntime.gc()
      log.info("Memory Used: " + math.round(10 * (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (Math.pow(1000, 3))) / 10.0 + "(GB)")
  }




}

case object Tick

