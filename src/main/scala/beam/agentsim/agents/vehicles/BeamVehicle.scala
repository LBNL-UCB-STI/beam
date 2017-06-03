package beam.agentsim.agents.vehicles

import beam.agentsim.agents.BeamAgent
import beam.agentsim.agents.BeamAgent.BeamAgentData
import beam.agentsim.events.SpaceTime
import org.matsim.api.core.v01.{Id, Identifiable}

/**
  * @author dserdiuk
  */

abstract class Dimension

case class VehicleData(dim: Dimension, trajectory: Trajectory) extends BeamAgentData


trait BeamVehicle[T] extends Identifiable[T]{

  def id: Id[T]

  override def getId: Id[T] = id

  def driver: Option[BeamAgent[_]]

  /**
    * Other vehicle that carry this one. Like ferry or track may carry a car
    *
    * @return
    */
  def carrier: Option[BeamVehicle[_]]

  def passengers: List[BeamVehicle[_]]

  def vehicleData: VehicleData

  def powerTrain: Powertrain

  def location(time: Double): SpaceTime = {
    carrier match  {
      case Some(vehicle) =>
        vehicle.location(time)
      case None =>
        vehicleData.trajectory.location(time)
    }
  }
}
