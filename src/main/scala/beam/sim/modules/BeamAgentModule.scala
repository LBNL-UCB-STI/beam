package beam.sim.modules

import akka.actor.ActorSystem
import beam.sim.akkaguice.{AkkaGuiceSupport, GuiceAkkaExtension}
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, BeamServicesImpl}
import com.google.inject._
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule

/**
  * Wires in BeamAgent Types
  *
  * Created by sfeygin on 2/6/17.
  */
class BeamAgentModule(val beamConfig: BeamConfig) extends AbstractModule with AkkaGuiceSupport with ScalaModule {

  @Provides @Singleton
  def provideActorSystem(injector: Injector, config: Config): ActorSystem = {
    println(config)
    val system = ActorSystem("ClusterSystem", config)
    // add the GuiceAkkaExtension to the system, and initialize it with the Guice injector
    GuiceAkkaExtension(system).initialize(injector)
    system
  }

  @Provides @Singleton
  def provideBeamServices(injector: Injector): BeamServices = {
    new BeamServicesImpl(injector)
  }

  override def configure(): Unit = ()

}
