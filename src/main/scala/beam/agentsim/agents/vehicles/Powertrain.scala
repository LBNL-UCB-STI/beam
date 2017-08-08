package beam.agentsim.agents.vehicles

/**
  *
  * @param joulesPerMeter joules per meter
  */
class Powertrain(joulesPerMeter: Double) {

  def estimateConsumptionAt(trajectory: Trajectory, time: Double) = {
    val path = trajectory.computePath(time)
    joulesPerMeter * path
  }
}

object Powertrain {
  def PowertrainFromMilesPerGallon(milesPerGallon: Double): Powertrain = new Powertrain(milesPerGallon / 120276367 * 1609.34) // 1609.34 m / mi; 120276367 J per gal
}
