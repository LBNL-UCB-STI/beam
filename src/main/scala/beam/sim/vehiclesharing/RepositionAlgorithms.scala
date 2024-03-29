package beam.sim.vehiclesharing

import beam.agentsim.agents.vehicles.VehicleManager
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Id

object RepositionAlgorithms {

  def lookup(
    config: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition
  ): RepositionAlgorithmType = {
    config.name match {
      case "min-availability-undersupply-algorithm" =>
        AvailabilityBasedRepositioningType(config)
      case "min-availability-observed-algorithm" =>
        AvailabilityBehaviorBasedRepositioningType(config)
      case _ =>
        throw new RuntimeException("Unknown reposition algorithm type")
    }
  }
}

trait RepositionAlgorithmType {

  def getInstance(
    vehicleManagerId: Id[VehicleManager],
    beamServices: BeamServices
  ): RepositionAlgorithm
  def getRepositionTimeBin: Int
  def getStatTimeBin: Int
}

case class AvailabilityBehaviorBasedRepositioningType(
  params: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition
) extends RepositionAlgorithmType {

  override def getInstance(
    vehicleManager: Id[VehicleManager],
    beamServices: BeamServices
  ): RepositionAlgorithm = {
    AvailabilityBehaviorBasedRepositioning(
      params.repositionTimeBin,
      params.statTimeBin,
      params.min_availability_undersupply_algorithm.get.matchLimit,
      vehicleManager,
      beamServices
    )
  }
  def getRepositionTimeBin: Int = params.repositionTimeBin
  def getStatTimeBin: Int = params.statTimeBin
}

case class AvailabilityBasedRepositioningType(
  params: BeamConfig.Beam.Agentsim.Agents.Vehicles.SharedFleets$Elm.Reposition
) extends RepositionAlgorithmType {

  override def getInstance(
    vehicleManagerId: Id[VehicleManager],
    beamServices: BeamServices
  ): RepositionAlgorithm = {
    AvailabilityBasedRepositioning(
      params.repositionTimeBin,
      params.statTimeBin,
      params.min_availability_undersupply_algorithm.get.matchLimit,
      vehicleManagerId,
      beamServices
    )
  }
  def getRepositionTimeBin: Int = params.repositionTimeBin
  def getStatTimeBin: Int = params.statTimeBin
}
