package beam.metasim.agents

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import beam.metasim.agents.BeamAgentProtocol.{CreatePersonAgent, PersonAgentCreated}
import beam.metasim.agents.PersonAgent.PersonAgentFactory
import beam.metasim.akkaguice.{ActorInject, AkkaGuiceSupport}
import com.google.inject.{Inject, Injector}


trait BeamAgentCreatorService extends Actor with ActorInject

/**
  * Injector of child [[PersonAgent]]s where the ID is provided externally.
  * See [[AkkaGuiceSupport]] for info on how this works. Returns the ActorRef to the parent.
  *
  * This [[Actor]] implements the reactive analog of the GOF factory pattern, permitting
  * concurrent spawning of actors and (optionally) controlling
  * respawning of dead/crashed actors
  * (XXX: unless this is just 'handled' by the persistence module).
  *
  *
  * @param injector           a Guice [[Injector]]
  * @param personAgentFactory factory class in the [[PersonAgent]] object.
  *
  */
class PersonAgentCreatorService @Inject()(val injector: Injector, personAgentFactory: PersonAgentFactory) extends BeamAgentCreatorService {

  val log = Logging(context.system, this)

  override def receive: Receive = {
    case info: CreatePersonAgent =>
      val child: ActorRef = injectActor(personAgentFactory(info.id))
      sender ! PersonAgentCreated(info.id, child)
    case _ => log.info("received unknown message")
  }

}
