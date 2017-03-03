package beam.metasim.routing

import beam.metasim.sim.MetasimServices
import beam.playground.metasim.services.location.BeamRouterImpl
import com.google.inject.{Inject, Provider}
import com.typesafe.config.Config
import org.matsim.core.router.{RoutingModule, TripRouter}

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterModuleProvider {
}

class BeamRouterModuleProvider @Inject()(config: Config,metasimServices: MetasimServices, tripRouter: TripRouter) extends Provider[RoutingModule] {
  // XXXX: Get router params from config and use BeamRouterImpl (to be redefined?)
  override def get(): RoutingModule = {
    val dr=new DummyRouter(metasimServices,tripRouter)
    new BeamRouterImpl("ha","ha")
  }
}
