package beam.utils.beamToVia.beamEventsFilter

import beam.utils.beamToVia.beamEvent.BeamEvent

import scala.collection.mutable

object PersonEvents {
  def apply(personId: String, event: BeamEvent): PersonEvents = new PersonEvents(personId, mutable.MutableList(event))
}

case class PersonEvents(personId: String, events: mutable.MutableList[BeamEvent]) {}
