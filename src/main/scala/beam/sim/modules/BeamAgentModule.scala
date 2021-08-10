package beam.sim.modules

import akka.actor.ActorSystem
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
class BeamAgentModule(val beamConfig: BeamConfig) extends AbstractModule with ScalaModule {

  @Provides @Singleton
  def provideActorSystem(config: Config): ActorSystem = {
    ActorSystem(beamConfig.beam.actorSystemName, config)
  }

  @Provides @Singleton
  def provideBeamServices(injector: Injector): BeamServices = {
    new BeamServicesImpl(injector)
  }

  override def configure(): Unit = ()

}
