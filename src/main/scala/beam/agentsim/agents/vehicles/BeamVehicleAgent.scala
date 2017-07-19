package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import beam.agentsim.events.resources.vehicle.GetVehicleLocationEvent
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import akka.pattern.pipe
import beam.agentsim.agents.InitializeTrigger

import scala.concurrent.ExecutionContext.Implicits.global

/**
  *
  * @author dserdiuk
  */
object BeamVehicleAgent {

  def props(vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain) = {
    //TODO: trajectory looks irrelevant here,
    //we need to have person/owner of vehicle to build trajectory from activity plan, right ?
    Props(classOf[BeamVehicleAgent], vehicleId,
      VehicleData.vehicle2vehicleData(matSimVehicle), powertrain, None, Nil, None )
  }
}

class BeamVehicleAgent(val id: Id[Vehicle], val vehicleData: VehicleData,
                       val powerTrain: Powertrain,
                       var carrier: Option[ActorRef] = None,
                       var passengers: List[ActorRef] = Nil,
                       var driver: Option[ActorRef] = None) extends BeamVehicle {

  private var _trajectory: Trajectory = null

  private var positionOnTrajectory = 0

  def moveNext() = {
    positionOnTrajectory = positionOnTrajectory + 1
  }

  override def trajectory: Trajectory = _trajectory

  override def receive: Receive = {
    case GetVehicleLocationEvent(time) =>
      location(time) pipeTo sender()
    case InitializeTrigger(_) =>
      log.debug(s"BeamVehicle ${self.path.name} has been initialized ")
    case _ =>
      throw new UnsupportedOperationException
  }
}


/**
  * Defines basic dimension of vehicle
  *
  * @param capacity number of passengers
  * @param length   length in meters
  */
case class VehicleDimension(capacity: Int, length: Double) extends Dimension
