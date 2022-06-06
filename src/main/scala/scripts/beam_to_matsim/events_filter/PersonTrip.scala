package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.BeamPathTraversal

import scala.collection.mutable

object PersonTrip {

  def apply(personId: String, pte: BeamPathTraversal): PersonTrip =
    new PersonTrip(personId, mutable.MutableList(pte))
}

case class PersonTrip(personId: String, trip: mutable.MutableList[BeamPathTraversal]) {}
