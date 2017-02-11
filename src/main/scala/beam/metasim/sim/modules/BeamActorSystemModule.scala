package beam.metasim.sim.modules

import akka.actor.ActorSystem
import beam.metasim.agents.BeamAgent
import beam.metasim.akkaguice.GuiceAkkaExtension
import beam.metasim.sim.modules.BeamActorSystemModule.ActorSystemProvider
import beam.playground.metasim.services.location.BeamRouter
import com.google.inject.{AbstractModule, Inject, Injector, Provider}
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.router.RoutingModule
import org.slf4j.LoggerFactory

/**
  * Beam actor simulation
  *  - This will eventually extend MobSim, but for now, just return the [[ActorSystem]]
  * Created by sfeygin on 1/28/17.
  */
object BeamActorSystemModule {
  private val logger = LoggerFactory.getLogger(classOf[BeamAgent])

  class ActorSystemProvider @Inject() (val config: Config, val injector: Injector) extends Provider[ActorSystem] {
    override def get(): ActorSystem = {
      val system = ActorSystem("beam-actor-system", config)
      // add the GuiceAkkaExtension to the system, and initialize it with the Guice injector
      GuiceAkkaExtension(system).initialize(injector)
      system
    }
  }
}


class BeamActorSystemModule extends AbstractModule with ScalaModule {
  override def configure() {
    bind[ActorSystem].toProvider[ActorSystemProvider].asEagerSingleton()

  }

}





