package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, Props}
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.attributable.Attributes
import org.matsim.vehicles.{Vehicle, VehicleType}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
  *
  * @author dserdiuk
  */

case class VehicleDataImpl(vehicleTypeName: String, vehicleClassName: String,
                           matSimVehicle: Vehicle, attributes: Attributes) extends VehicleData {
  override def getType: VehicleType = matSimVehicle.getType

  override def getId: Id[Vehicle] = matSimVehicle.getId
}

/**
  * Defines basic dimension of vehicle
  *
  * @param capacity number of passengers
  * @param length   length in meters
  */
case class VehicleDimension(capacity: Int, length: Double) extends Dimension


object BeamVehicleAgent {

  def props(vehicleId: Id[Vehicle], matSimVehicle: Vehicle, powertrain: Powertrain) = {
    //TODO: trajectory looks irrelevant here,
    //we need to have person/owner of vehicle to build trajectory from activity plan, right ?
    Props(classOf[BeamVehicleAgent], vehicleId,
      VehicleData.vehicle2vehicleData(matSimVehicle), powertrain, None)
  }
}

class BeamVehicleAgent(val id: Id[Vehicle], val data: VehicleDataImpl,
                       val powerTrain: Powertrain,
                       var carrier: Option[ActorRef]) extends BeamVehicle {

  private var _driver: ActorRef = null
  private var _passengers: mutable.ListBuffer[ActorRef] =  ListBuffer()
  private var _trajectory: Trajectory = null

  private var positionOnTrajectory = 0

  def moveNext() = {
    positionOnTrajectory = positionOnTrajectory + 1
  }

  override def trajectory: Trajectory = _trajectory

  override protected def setDriver(newDriver: ActorRef): Unit = {
    _driver = newDriver
  }

  override protected def pickupPassengers(newPassengers: List[ActorRef]): Unit = {
    _passengers ++= newPassengers
  }

  override protected def dropOffPassengers(passengersToDrop: List[ActorRef]): List[ActorRef] = {
    var droppedPassengers = ListBuffer[ActorRef]()
    for (dropPsng <- passengersToDrop) {
      val index = passengers.indexOf(dropPsng)
      if (index > -1) {
        droppedPassengers += _passengers.remove(index)
      }
    }
    droppedPassengers.toList
  }

  override def driver: Option[ActorRef] = Option(_driver)

  override def passengers: List[ActorRef] = _passengers.toList

}
