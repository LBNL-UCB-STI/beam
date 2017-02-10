package beam.metasim.playground.sid.sim.modules

import akka.actor.{Actor, ActorRef, ActorSystem}
import beam.metasim.agents.PersonAgent
import beam.metasim.playground.sid.akkaguice.GuiceAkkaActorRefProvider
import com.google.inject.{AbstractModule, Inject, Provides}
import com.google.inject.name.{Named, Names}
import net.codingwell.scalaguice.ScalaModule

/**
  * Wires in BeamAgent Types
  *
  * Created by sfeygin on 2/6/17.
  */

class BeamAgentModule extends AbstractModule with ScalaModule with GuiceAkkaActorRefProvider{

  override def configure(): Unit = {
    bind[Actor].annotatedWith(Names.named(PersonAgent.name)).to[PersonAgent]
  }

  @Provides
  @Named("PersonAgent")
  def providePersonAgentRef(@Inject() system: ActorSystem): ActorRef = provideActorRef(system, PersonAgent.name)

}
