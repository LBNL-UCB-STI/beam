package beam.metasim.playground.sid.sim.modules

import beam.metasim.playground.sid.sim.MetaSim
import beam.metasim.playground.sid.sim.modules.BeamRouterModuleProvider.BeamRouterModuleProvider
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.router.RoutingModule

/**
  * Created by sfeygin on 2/9/17.
  */
class MetaSimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[RoutingModule].toProvider[BeamRouterModuleProvider]
    bind[Mobsim].to[MetaSim].asEagerSingleton()
  }
}
