package beam.agentsim.agents
import java.util

import akka.actor.{ActorContext, ActorRef, FSM, SupervisorStrategy}
import akka.event.LoggingAdapter
import beam.agentsim.agents.PersonAgent.PersonData
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

import scala.concurrent.duration.FiniteDuration

/**
  * BEAM
  */
class PersonWhoUsesTaxi(override val id: Id[PersonAgent], override val data: PersonData, override val beamServices: BeamServices)  extends PersonAgent with CanUseTaxi{
}
