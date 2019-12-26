package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.BeamPathTraversal

import scala.collection.mutable

object VehicleTrip {

  def apply(pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(pte.vehicleId, mutable.MutableList(pte))

  def apply(vehicleId: String, pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(vehicleId, mutable.MutableList(pte))
}

case class VehicleTrip(vehicleId: String, trip: mutable.MutableList[BeamPathTraversal]) {}
