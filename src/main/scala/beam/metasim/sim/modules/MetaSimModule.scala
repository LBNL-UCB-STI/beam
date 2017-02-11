package beam.metasim.sim.modules

import beam.playground.metasim.services.location.RelaxedTravelTime
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.router.RoutingModule
import org.matsim.core.router.util.TravelTime

/**
  * Created by sfeygin on 2/9/17.
  */
class MetaSimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[RoutingModule].toProvider[BeamRouterModuleProvider]
    bind[TravelTime].to[RelaxedTravelTime]
  }
}
