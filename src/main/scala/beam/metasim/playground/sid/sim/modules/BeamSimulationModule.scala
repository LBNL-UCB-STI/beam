package beam.metasim.playground.sid.sim.modules

import akka.actor.ActorSystem
import beam.metasim.playground.sid.agents.BeamAgent
import beam.metasim.playground.sid.akkaguice.GuiceAkkaExtension
import beam.metasim.playground.sid.sim.modules.BeamSimulationModule.ActorSystemProvider
import beam.metasim.playground.sid.usecases.{RouterService, RouterServiceI}
import com.google.inject.{AbstractModule, Inject, Injector, Provider}
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.StartupEvent
import org.matsim.core.controler.listener.StartupListener
import org.slf4j.LoggerFactory

/**
  * Beam actor simulation
  *  - This will eventually extend MobSim, but for now, just return the [[ActorSystem]]
  * Created by sfeygin on 1/28/17.
  */
object BeamSimulationModule {
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


class BeamSimulationModule extends AbstractModule with ScalaModule {
  override def configure() {
    bind[ActorSystem].toProvider[ActorSystemProvider].asEagerSingleton()
    bind[RouterService].to[RouterServiceI]

  }

}





