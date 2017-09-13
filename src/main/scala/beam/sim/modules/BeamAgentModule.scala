package beam.sim.modules

import akka.actor.ActorSystem
import beam.sim.akkaguice.{AkkaGuiceSupport, GuiceAkkaExtension}
import beam.sim.{BeamServices, BeamServicesImpl}
import beam.sim.modules.BeamAgentModule.{ActorSystemProvider, BeamServicesProvider}
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

  class ActorSystemProvider @Inject()(val injector: Injector, config: Config) extends Provider[ActorSystem] {
    override def get(): ActorSystem = {
      val system = ActorSystem("beam-actor-system", config)
      // add the GuiceAkkaExtension to the system, and initialize it with the Guice injector
      GuiceAkkaExtension(system).initialize(injector)
      system
    }
  }
  class BeamServicesProvider @Inject()(val injector: Injector) extends Provider[BeamServices] {
    override def get(): BeamServices = {
      new BeamServicesImpl(injector)
    }
  }
}

class BeamAgentModule extends AbstractModule with AkkaGuiceSupport with ScalaModule {
  override def configure(): Unit = {
    bind[ActorSystem].toProvider[ActorSystemProvider].asEagerSingleton()
    bind[BeamServices].toProvider[BeamServicesProvider].asEagerSingleton()
  }
}
