package beam.sim.modules

import beam.agentsim.events.BeamEventsHandling
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.controler.corelisteners.EventsHandling

/**
  * All non-agent/Actor MetaSim-specific services
  *     and submodules shall be bound here.
  * Created by sfeygin on 2/9/17.
  */
class AgentsimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[EventsHandling].to[BeamEventsHandling]
  }
}
