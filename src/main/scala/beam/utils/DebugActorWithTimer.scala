package beam.utils

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.agents.rideHail.RideHailManager.DebugRideHailManagerDuringExecution
import beam.agentsim.scheduler.BeamAgentScheduler.Monitor


class DebugActorWithTimer(val rideHailManager:ActorRef, val scheduler:ActorRef) extends Actor with ActorLogging{
  def receive = {
    case Tick ⇒
      log.info(DebugLib.gcAndGetMemoryLogMessage("Memory use after GC: "))
      rideHailManager ! DebugRideHailManagerDuringExecution
      scheduler ! Monitor
  }




}

case object Tick

