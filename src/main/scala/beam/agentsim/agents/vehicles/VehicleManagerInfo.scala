package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.vehiclesharing.VehicleManager
import enumeratum._
import org.matsim.api.core.v01.Id

import scala.collection.immutable

case class VehicleManagerInfo(managerId: Id[VehicleManager], managerType: VehicleManagerType)

object VehicleManagerInfo {

  def create(
    managerId: String,
    vehicleType: BeamVehicleType,
    isRideHail: Boolean = false,
    isShared: Boolean = false,
  ): VehicleManagerInfo = {
    VehicleManagerInfo(
      Id.create(managerId, classOf[VehicleManager]),
      VehicleManagerType.getManagerType(vehicleType.vehicleCategory, isRideHail, isShared)
    )
  }

  def apply(
    managerId: Id[VehicleManager],
    vehicleType: BeamVehicleType,
    isRideHail: Boolean = false,
    isShared: Boolean = false,
  ): VehicleManagerInfo =
    VehicleManagerInfo(managerId, VehicleManagerType.getManagerType(vehicleType.vehicleCategory, isRideHail, isShared))
}

sealed trait VehicleManagerType extends EnumEntry

object VehicleManagerType extends Enum[VehicleManagerType] {
  val values: immutable.IndexedSeq[VehicleManagerType] = findValues

  case object Bodies extends VehicleManagerType //for human bodies
  case object Cars extends VehicleManagerType //for private cars
  case object Bikes extends VehicleManagerType //for private bikes
  case object Carsharing extends VehicleManagerType //for shared fleet of type car
  case object SharedMicromobility extends VehicleManagerType //for shared bikes and scooters
  case object Ridehail extends VehicleManagerType //for ridehail
  case object Freight extends VehicleManagerType

  def getManagerType(
    vehicleCategory: VehicleCategory,
    isRideHail: Boolean = false,
    isShared: Boolean = false
  ): VehicleManagerType = {

    vehicleCategory match {
      case _ if isRideHail                  => Ridehail
      case VehicleCategory.Body             => Bodies
      case VehicleCategory.Bike if isShared => SharedMicromobility
      case VehicleCategory.Bike             => Bikes
      case VehicleCategory.Car if isShared  => Carsharing
      case VehicleCategory.Car              => Cars
      case _                                => Freight
    }
  }

}
