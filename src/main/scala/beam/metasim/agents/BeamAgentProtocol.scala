package beam.metasim.agents

import akka.actor.ActorRef
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

/**
  * Message case classes describing work for BeamAgents, subclasses, and interacting entities.
  *
  * XXXX: Convert to traits.
  *
  * Created by sfeygin on 2/14/17.
  */
object BeamAgentProtocol {
  trait CreateBeamAgent
  trait CreateBeamAgents

  case class CreatePersonAgent(id: Id[Person]) extends CreateBeamAgent
  case class CreatePersonAgents(ids: Vector[Id[Person]]) extends CreateBeamAgents

  abstract class BeamAgentCreated(id:Id[_], actorRef: ActorRef)
  case class PersonAgentCreated(id:Id[Person], actorRef:ActorRef) extends BeamAgentCreated(id=id,actorRef=actorRef)
}
