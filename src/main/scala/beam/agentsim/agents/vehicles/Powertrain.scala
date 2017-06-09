package beam.agentsim.agents.vehicles

/**
  *
  * @param energyPerUnit joules per meter
  */
class Powertrain(energyPerUnit: Double) {

  def estimateConsumptionAt(trajectory: Trajectory, time: Double) = {
    val path = trajectory.computePath(time)
    energyPerUnit * path
  }
}
