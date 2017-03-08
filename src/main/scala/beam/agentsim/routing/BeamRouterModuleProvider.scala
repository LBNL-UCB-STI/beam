package beam.agentsim.routing

import beam.agentsim.sim.AgentsimServices
import com.google.inject.{Inject, Provider}
import glokka.Registry
import org.matsim.core.router.{RoutingModule, TripRouter}

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterModuleProvider {
}

class BeamRouterModuleProvider @Inject()(agentsimServices: AgentsimServices, tripRouter: TripRouter) extends Provider[RoutingModule] {
  // XXXX: Get router params from config and use BeamRouterImpl (to be redefined?)
  override def get(): DummyRouter = {
    AgentsimServices.registry ! Registry.Register("agent-router", DummyRouter.props(agentsimServices, tripRouter))
    new DummyRouter(agentsimServices, tripRouter)
  }
}
