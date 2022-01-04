package beam.utils.beam_to_matsim.events_filter

import beam.utils.beam_to_matsim.events.BeamPathTraversal

import scala.collection.mutable

object VehicleTrip {

  def apply(pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(pte.vehicleId, mutable.ListBuffer(pte))

  def apply(vehicleId: String, pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(vehicleId, mutable.ListBuffer(pte))
}

case class VehicleTrip(vehicleId: String, trip: mutable.ListBuffer[BeamPathTraversal]) {}
