package beam.utils.beam_to_matsim.events

import org.matsim.api.core.v01.events.Event

object BeamEventReader {

  private def readPTE(event: Event): Option[BeamEvent] = {
    val bpte = BeamPathTraversal(event)
    if (bpte.linkIds.nonEmpty) Some(bpte)
    else None
  }

  def read(event: Event): Option[BeamEvent] = event.getEventType match {
    case BeamPathTraversal.EVENT_TYPE       => readPTE(event)
    case BeamPersonLeavesVehicle.EVENT_TYPE => Some(BeamPersonLeavesVehicle(event))
    case BeamPersonEntersVehicle.EVENT_TYPE => Some(BeamPersonEntersVehicle(event))
    case BeamModeChoice.EVENT_TYPE          => Some(BeamModeChoice(event))
    case BeamActivityStart.EVENT_TYPE       => Some(BeamActivityStart(event))
    case BeamActivityEnd.EVENT_TYPE         => Some(BeamActivityEnd(event))
    case _                                  => None
  }
}

trait BeamEvent {
  val time: Double
}
