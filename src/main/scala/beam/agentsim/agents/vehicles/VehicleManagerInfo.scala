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

sealed abstract class VehicleManagerType(val isPrivate: Boolean, val isShared: Boolean = false) extends EnumEntry

object VehicleManagerType extends Enum[VehicleManagerType] {
  val values: immutable.IndexedSeq[VehicleManagerType] = findValues

  case object Bodies extends VehicleManagerType(isPrivate = true) //for human bodies
  case object Cars extends VehicleManagerType(isPrivate = true) //for private cars
  case object Bikes extends VehicleManagerType(isPrivate = true) //for private bikes
  case object Carsharing extends VehicleManagerType(isPrivate = false, isShared = true) //for shared fleet of type car
  case object SharedMicromobility extends VehicleManagerType(isPrivate = false, isShared = true) //for shared bikes and scooters
  case object Ridehail extends VehicleManagerType(isPrivate = false) //for ridehail
  case object Freight extends VehicleManagerType(isPrivate = false)

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
