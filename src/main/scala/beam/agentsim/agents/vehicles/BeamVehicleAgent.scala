package beam.agentsim.agents.vehicles

import akka.actor.ActorRef
import beam.agentsim.events.resources.vehicle.GetVehicleLocationEvent
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import akka.pattern.pipe
import scala.concurrent.ExecutionContext.Implicits.global

/**
  *
  * @author dserdiuk
  */
class BeamVehicleAgent(val id: Id[Vehicle], val vehicleData: VehicleData,
                       val powerTrain: Powertrain,
                       val trajectory: Trajectory,
                       var carrier: Option[ActorRef] = None,
                       var passengers: List[ActorRef] = Nil,
                       var driver: Option[ActorRef] = None) extends BeamVehicle {

  private var positionOnTrajectory = 0

  def moveNext() = {
    positionOnTrajectory = positionOnTrajectory + 1
  }

  override def receive: Receive = {
    case GetVehicleLocationEvent(time) =>
      location(time) pipeTo sender()

  }
}


/**
  * Defines basic dimension of vehicle
  *
  * @param capacity number of passengers
  * @param length   length in meters
  */
case class VehicleDimension(capacity: Int, length: Double) extends Dimension
