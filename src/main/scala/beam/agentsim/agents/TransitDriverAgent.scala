package beam.agentsim.agents

import akka.actor.Props
import beam.agentsim.agents.BeamAgent.{BeamAgentData, BeamAgentInfo, BeamAgentState, Error, Uninitialized}
import beam.agentsim.agents.TransitDriverAgent.{Idle, TransitDriverData, Traveling}
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id

/**
  * BEAM
  */
object TransitDriverAgent {
  def props(services: BeamServices, transitDriverId: Id[TransitDriverAgent]) = Props(classOf[TransitDriverAgent], services, transitDriverId, TransitDriverData())
  case class TransitDriverData() extends BeamAgentData
  case object Idle extends BeamAgentState {
    override def identifier = "Idle"
  }
  case object Traveling extends BeamAgentState {
    override def identifier = "Traveling"
  }
}

class TransitDriverAgent(val beamServices: BeamServices,
                         override val id: Id[TransitDriverAgent],

                         override val data: TransitDriverData) extends
  BeamAgent[TransitDriverData] with HasServices with DrivesVehicle[TransitDriverData] {
  override def logPrefix(): String = s"TransitDriverAgent:$id "

  when(Idle) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }
  when(Traveling) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      logError(s"Unrecognized message ${msg}")
      goto(Error)
  }

  chainedWhen(Uninitialized){
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), info: BeamAgentInfo[TransitDriverData]) =>
      beamServices.schedulerRef ! CompletionNotice(triggerId)
      goto(Idle)
  }

//  chainedWhen(Idle) {
//  }

//  chainedWhen(Traveling) {
//  }

}
