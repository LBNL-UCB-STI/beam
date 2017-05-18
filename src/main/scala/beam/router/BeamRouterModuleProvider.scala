package beam.router

import beam.agentsim.sim.AgentsimServices
import com.google.inject.{Inject, Provider}
import org.matsim.core.router.{RoutingModule, TripRouter}

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterModuleProvider {
}

class BeamRouterModuleProvider @Inject()(agentsimServices: AgentsimServices, tripRouter: TripRouter) extends Provider[RoutingModule] {
  // XXXX: Get router params from config and use BeamRouterImpl (to be redefined?)
//  override def get(): OpenTripPlannerRouter = {
//    val otpRouter = new OpenTripPlannerRouter(agentsimServices)
//    AgentsimServices.registry ! Registry.Register("agent-router", otpRouter.self)
//    otpRouter
//  }
  override def get(): DummyRouter = {
    new DummyRouter(agentsimServices, tripRouter)
  }
}
