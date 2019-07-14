package beam.utils.beamToVia.beamEventsFilter

import beam.utils.beamToVia.beamEvent.BeamPathTraversal

import scala.collection.mutable

object PersonTrip {

  def apply(personId: String, pte: BeamPathTraversal): PersonTrip =
    new PersonTrip(personId, mutable.MutableList(pte))
}

case class PersonTrip(personId: String, trip: mutable.MutableList[BeamPathTraversal]) {}
