package beam.router.skim

import beam.agentsim.infrastructure.taz.H3TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.events.handler.BasicEventHandler

abstract class AbstractBeamSkimmer2(h3taz: H3TAZ) extends BasicEventHandler with LazyLogging {
  def persist(event: org.matsim.core.controler.events.IterationEndsEvent)
}
