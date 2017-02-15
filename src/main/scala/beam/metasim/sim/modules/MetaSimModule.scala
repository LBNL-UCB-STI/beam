package beam.metasim.sim.modules

import akka.actor.Scheduler
import beam.metasim.agents.PersonAgentCreatorService
import beam.metasim.routing.BeamRouterModuleProvider
import beam.playground.metasim.services.location.RelaxedTravelTime
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.router.RoutingModule
import org.matsim.core.router.util.TravelTime

/**
  * All non-agent/Actor MetaSim-specific services
  *     and submodules shall be bound here.
  * Created by sfeygin on 2/9/17.
  */
class MetaSimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {

    bind[Scheduler].asEagerSingleton()
    bind[PersonAgentCreatorService].asEagerSingleton()
    bind[RoutingModule].toProvider[BeamRouterModuleProvider]
    bind[TravelTime].to[RelaxedTravelTime]
  }
}
