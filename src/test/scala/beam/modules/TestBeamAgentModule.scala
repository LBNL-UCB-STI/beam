package beam.modules

import akka.actor.ActorSystem
import beam.metasim.akkaguice.{AkkaGuiceSupport, GuiceAkkaExtension}
import beam.metasim.sim.modules.BeamAgentModule.ActorSystemProvider
import com.google.inject.{AbstractModule, Inject, Injector, Provider}
import com.typesafe.config.Config
import net.codingwell.scalaguice.ScalaModule
import org.slf4j.{Logger, LoggerFactory}

class TestBeamAgentModule extends AbstractModule with AkkaGuiceSupport with ScalaModule {
  override def configure(): Unit = {
//    bind[ActorSystem].toProvider[ActorSystemProvider].asEagerSingleton()
//    bind[PersonAgentCreator].asEagerSingleton()
//    bindConstant.annotatedWith(Names.named("personName")).to(classOf[Id[Person]])
//    bind[MetaSimServices].asEagerSingleton()
//    bindActorFactory[PersonAgent, PersonAgentFactory]()
  }
}



