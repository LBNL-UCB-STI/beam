package beam.analysis.plots.filterevent

import org.matsim.api.core.v01.events.Event

trait FilterEvent {
  def shouldProcessEvent(event: Event): Boolean
  def graphNamePreSuffix: String = ""
}
