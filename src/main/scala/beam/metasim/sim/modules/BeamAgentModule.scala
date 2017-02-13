package beam.metasim.sim.modules

import akka.actor.ActorSystem
import beam.metasim.agents.{PersonAgent, PersonAgentCreator, Scheduler}
import beam.metasim.agents.PersonAgent.PersonAgentFactory
import beam.metasim.akkaguice.{AkkaGuiceSupport, GuiceAkkaExtension}
import beam.metasim.sim.MetaSimServices
import beam.metasim.sim.modules.BeamAgentModule.ActorSystemProvider
import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Inject, Injector, Provider}
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.slf4j.{Logger, LoggerFactory}

/**
  * Wires in BeamAgent Types
  *
  * Created by sfeygin on 2/6/17.
  */
object BeamAgentModule {
  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamAgentModule])

  class ActorSystemProvider @Inject()(val config: Config, val injector: Injector) extends Provider[ActorSystem] {
    override def get(): ActorSystem = {
      val system = ActorSystem("beam-actor-system", config)
      // add the GuiceAkkaExtension to the system, and initialize it with the Guice injector
      GuiceAkkaExtension(system).initialize(injector)
      system
    }
  }
}


class BeamAgentModule extends AbstractModule with AkkaGuiceSupport with ScalaModule {
  override def configure(): Unit = {
    bind[ActorSystem].toProvider[ActorSystemProvider].asEagerSingleton()
    bind[Scheduler].asEagerSingleton()
    bind[PersonAgentCreator].asEagerSingleton()
    bindConstant.annotatedWith(Names.named("personName")).to(classOf[Id[Person]])
    bind[MetaSimServices].asEagerSingleton()
    bindActorFactory[PersonAgent, PersonAgentFactory]()
  }
}
