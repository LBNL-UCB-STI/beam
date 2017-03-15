package beam.agentsim.sim.modules

import akka.actor.ActorSystem
import beam.agentsim.agents.BeamAgentScheduler
import beam.agentsim.akkaguice.{AkkaGuiceSupport, GuiceAkkaExtension}
import beam.agentsim.config.BeamConfig
import beam.agentsim.sim.AgentsimServices
import beam.agentsim.sim.modules.BeamAgentModule.ActorSystemProvider
import com.google.inject.{AbstractModule, Inject, Injector, Provider}
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule
import org.slf4j.{Logger, LoggerFactory}

/**
  * Wires in BeamAgent Types
  *
  * Created by sfeygin on 2/6/17.
  */
object BeamAgentModule {
  private val logger: Logger = LoggerFactory.getLogger(classOf[BeamAgentModule])

  class ActorSystemProvider @Inject()(val injector: Injector)(implicit config: Config) extends Provider[ActorSystem] {
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
    bind[AgentsimServices].asEagerSingleton()
    bind[BeamAgentScheduler].asEagerSingleton()
  }
}
