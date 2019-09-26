package beam.utils

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.ridehail.RideHailManager.DebugRideHailManagerDuringExecution
import beam.agentsim.scheduler.BeamAgentScheduler.Monitor

class DebugActorWithTimer(val rideHailManager: ActorRef, val scheduler: ActorRef) extends Actor with ActorLogging {

  def receive: PartialFunction[Any, Unit] = {
    case Tick =>
      log.info(DebugLib.getMemoryLogMessage("Memory use after GC: "))
      rideHailManager ! DebugRideHailManagerDuringExecution
      scheduler ! Monitor
  }
}

case object Tick
