package beam.analysis.plots.filterevent

import org.matsim.api.core.v01.events.Event

object AllEventsFilter extends FilterEvent {
  override def shouldProcessEvent(event: Event): Boolean = true
}
