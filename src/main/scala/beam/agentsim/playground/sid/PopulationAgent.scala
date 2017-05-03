package beam.agentsim.playground.sid

import akka.actor.SupervisorStrategy.{Restart, Resume, Stop}
import akka.actor.{Actor, OneForOneStrategy}
import beam.agentsim.agents.PersonAgent
import beam.agentsim.playground.sid.BeamExceptions.{BeamAgentRestartException, BeamAgentResumeException, BeamAgentStopException}
import beam.agentsim.playground.sid.PopulationAgent.PopulationAgentProtocol.CreateChildAgent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * Created by sfeygin on 3/29/17.
  */
class PopulationAgent(popMap: Map[Id[Person],Person]) extends Actor{

  override def receive: Receive = {
    case msg: CreateChildAgent=>context.actorOf(PersonAgent.props(msg.id, popMap(msg.id).getSelectedPlan))
  }

  override def supervisorStrategy = OneForOneStrategy() {
    case _: BeamAgentRestartException => Restart
    case _: BeamAgentResumeException => Resume
    case _: BeamAgentStopException => Stop
  }
}

object PopulationAgent {
  object PopulationAgentProtocol{
    case class CreateChildAgent(id: Id[PersonAgent])
  }
}
