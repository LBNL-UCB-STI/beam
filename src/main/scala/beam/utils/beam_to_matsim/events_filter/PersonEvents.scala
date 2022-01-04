package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.BeamEvent

import scala.collection.mutable

object PersonEvents {
  def apply(personId: String, event: BeamEvent): PersonEvents = new PersonEvents(personId, mutable.ListBuffer(event))
}

case class PersonEvents(personId: String, events: mutable.ListBuffer[BeamEvent]) {}
