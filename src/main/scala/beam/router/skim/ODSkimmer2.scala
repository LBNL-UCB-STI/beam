package beam.router.skim
import beam.agentsim.infrastructure.taz.H3TAZ
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent

class ODSkimmer2(h3taz: H3TAZ) extends AbstractBeamSkimmer2(h3taz) {
  override def handleEvent(event: Event): Unit = {

  }
  override def persist(event: IterationEndsEvent): Unit = {

  }
}
