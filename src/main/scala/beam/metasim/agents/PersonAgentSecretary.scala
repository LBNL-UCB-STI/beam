package beam.metasim.agents

import akka.actor.{Actor, ActorRef, Props}
import beam.metasim.agents.BeamAgentProtocol.{CreatePersonAgents, PersonAgentCreated}
import beam.metasim.playground.sid.events.EventsSubscriber
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import scala.collection.mutable

/**
  * A creator AND manager of agents (alternative to [[PersonAgentCreatorService]].
  *
  * For now, this actor simply deals with the actor creation step,
  * but will be further specialized later.
  *
  * Responsibilities:
  *
  * -> Creates required agents and reports on their creation
  * to the creating actor (assumed to be some sort of simulationManager).
  *
  * XXXX: This is a service and may be abstracted
  * Created by sfeygin on 2/13/17.
  */
abstract class BeamAgentSecretary extends Actor

class PersonAgentSecretary(val simManager: ActorRef, val eventsSubscribers: List[EventsSubscriber]) extends BeamAgentSecretary {

  // Holds map of MATSim agent IDs -> ActorRefs. If missing, will need to recreate.
  val agents = mutable.Map.empty[Id[_ <: Person], ActorRef]

  override def receive: Receive = {
    // Agent is created:
    case agentData: PersonAgentCreated =>
      val ref = agentData.actorRef
      // monitor agent for death
      context.watch(ref)
    case request: CreatePersonAgents =>
      request.ids foreach { id => {
        agents.getOrElseUpdate(id, context.actorOf(Props(classOf[PersonAgent], id)))
      }
      }
  }
}
