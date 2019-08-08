package beam.utils.beamToVia.beamEventsFilter

import beam.utils.beamToVia.beamEvent.BeamPathTraversal

import scala.collection.mutable

object VehicleTrip {

  def apply(pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(pte.vehicleId, mutable.MutableList(pte))

  def apply(vehicleId: String, pte: BeamPathTraversal): VehicleTrip =
    new VehicleTrip(vehicleId, mutable.MutableList(pte))
}

case class VehicleTrip(vehicleId: String, trip: mutable.MutableList[BeamPathTraversal]) {}
