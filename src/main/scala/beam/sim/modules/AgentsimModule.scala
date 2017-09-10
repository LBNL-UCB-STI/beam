package beam.sim.modules

import beam.agentsim.events.handling.BeamEventsHandling
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.controler.corelisteners.EventsHandling
import org.matsim.core.events.ParallelEventsManagerImpl

class AgentsimModule  extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[EventsHandling].to[BeamEventsHandling]
  }
}
