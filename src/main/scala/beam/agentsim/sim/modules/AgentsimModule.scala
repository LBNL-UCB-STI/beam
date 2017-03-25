package beam.agentsim.sim.modules

import beam.agentsim.routing.BeamRouterModuleProvider
import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter
import beam.agentsim.events.BeamEventsHandling
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.controler.corelisteners.EventsHandling
import org.matsim.core.router.RoutingModule

/**
  * All non-agent/Actor MetaSim-specific services
  *     and submodules shall be bound here.
  * Created by sfeygin on 2/9/17.
  */
class AgentsimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[RoutingModule].toProvider[BeamRouterModuleProvider]
    bind[EventsHandling].to[BeamEventsHandling]
  }
}
