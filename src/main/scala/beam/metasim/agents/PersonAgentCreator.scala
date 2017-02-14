package beam.metasim.agents

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import beam.metasim.agents.PersonAgent.PersonAgentFactory
import beam.metasim.akkaguice.{ActorInject, AkkaGuiceSupport}
import com.google.inject.{Inject, Injector}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

// Message case class for agents.
case class CreatePersonAgent(id: Id[Person])

/**
  * Injector of child [[PersonAgent]]s where the ID is provided externally.
  * See [[AkkaGuiceSupport]] for info on how this works.
  *
  * @param injector a Guice [[Injector]]
  * @param personAgentFactory factory class in the [[PersonAgent]] object.
  *
  */
class PersonAgentCreator @Inject()(val injector: Injector, personAgentFactory: PersonAgentFactory) extends Actor with ActorInject{
  var agentPopulation: Map[Id[_<:Person],ActorRef] = Map.empty
  val log = Logging(context.system, this)


  override def receive: Receive = {
    case info:CreatePersonAgent =>
      val child: PersonAgent =  personAgentFactory.apply(info.id)

    case _ => log.info("received unknown message")
  }

}
