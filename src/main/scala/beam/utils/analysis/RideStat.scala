package beam.utils.analysis

case class RideStat(vehicleId: String, travelTime: Double, length: Double, freeFlowTravelTime: Double) {
  def speed: Double = if (travelTime == 0.0) Double.NaN else length / travelTime

  def freeFlowSpeed: Double = if (freeFlowTravelTime == 0.0) Double.NaN else length / freeFlowTravelTime
}
