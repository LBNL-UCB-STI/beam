package beam.utils.beamToVia.beamEvent

import org.matsim.api.core.v01.events.Event

object BeamEventReader {

  def read(event: Event): Option[BeamEvent] = event.getEventType match {
    case BeamPathTraversal.EVENT_TYPE       => Some(BeamPathTraversal(event))
    case BeamPersonLeavesVehicle.EVENT_TYPE => Some(BeamPersonLeavesVehicle(event))
    case BeamPersonEntersVehicle.EVENT_TYPE => Some(BeamPersonEntersVehicle(event))
    case _                                  => None
  }
}

trait BeamEvent {
  def time:Double
  def toXml: xml.Elem
}
