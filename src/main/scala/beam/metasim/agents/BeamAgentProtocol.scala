package beam.metasim.agents

import akka.actor.ActorRef
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * Message case classes describing work for BeamAgents, subclasses, and interacting entities.
  *
  * XXX: I propose to put messages shared between multiple actors in separate objects since
  * their class type is not necessarily tied to the eponymous actor.
  *
  * Created by sfeygin on 2/14/17.
  */
object BeamAgentProtocol {
  abstract case class CreateBeamAgent(id: Id[_])
  abstract case class CreateBeamAgents(ids: Vector[Id[_]])

  case class CreatePersonAgent(override val id: Id[Person]) extends CreateBeamAgent(id)
  case class CreatePersonAgents(override val ids: Vector[Id[Person]]) extends CreateBeamAgents(ids)

  abstract case class BeamAgentCreated(id:Id[_], actorRef: ActorRef)
  case class PersonAgentCreated(override val id:Id[Person], override val actorRef:ActorRef) extends BeamAgentCreated(id=id,actorRef=actorRef)
}
