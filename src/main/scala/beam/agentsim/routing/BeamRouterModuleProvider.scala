package beam.metasim.routing

import beam.agentsim.sim.AgentsimServices
import com.google.inject.{Inject, Provider}
import com.typesafe.config.Config
import org.matsim.core.router.{RoutingModule, TripRouter}

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterModuleProvider {
}

class BeamRouterModuleProvider @Inject()(config: Config, agentsimServices: AgentsimServices, tripRouter: TripRouter) extends Provider[RoutingModule] {
  // XXXX: Get router params from config and use BeamRouterImpl (to be redefined?)
  override def get(): BeamRouterImpl = {
    val dr=new DummyRouter(agentsimServices,tripRouter)
    new BeamRouterImpl("ha","ha")
  }
}
