package beam.agentsim.agents.vehicles

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import enumeratum._
import org.matsim.api.core.v01.Id

import scala.collection.immutable

trait VehicleManager

object VehicleManager {

  def createId(idString: String): Id[VehicleManager] = Id.create(idString, classOf[VehicleManager])

  def getType(
    vehicleType: BeamVehicleType,
    isRideHail: Boolean = false,
    isShared: Boolean = false,
    isFreight: Boolean = false,
  ): VehicleManagerType =
    VehicleManagerType.getManagerType(isRideHail, isShared, isFreight, Some(vehicleType.vehicleCategory))
}

sealed abstract class VehicleManagerType(
  val isPrivate: Boolean,
  val isShared: Boolean = false,
  val isFreight: Boolean = false
) extends EnumEntry

object VehicleManagerType extends Enum[VehicleManagerType] {
  val values: immutable.IndexedSeq[VehicleManagerType] = findValues

  case object Bodies extends VehicleManagerType(isPrivate = true) //for human bodies
  case object Cars extends VehicleManagerType(isPrivate = true) //for private cars
  case object Bikes extends VehicleManagerType(isPrivate = true) //for private bikes
  case object Carsharing extends VehicleManagerType(isPrivate = false, isShared = true) //for shared fleet of type car
  case object SharedMicromobility extends VehicleManagerType(isPrivate = false, isShared = true) //for shared bikes and scooters
  case object Ridehail extends VehicleManagerType(isPrivate = false) //for ridehail
  case object Freight extends VehicleManagerType(isPrivate = false, isFreight = true)
  case object Transit extends VehicleManagerType(isPrivate = false, isShared = true) // for transit

  def getManagerType(
    isRideHail: Boolean = false,
    isShared: Boolean = false,
    isFreight: Boolean = false,
    vehicleCategory: Option[VehicleCategory]
  ): VehicleManagerType = {

    vehicleCategory match {
      case Some(VehicleCategory.Body)              => Bodies
      case Some(VehicleCategory.Bike) if isShared  => SharedMicromobility
      case Some(VehicleCategory.Bike)              => Bikes
      case Some(VehicleCategory.Car) if isRideHail => Ridehail
      case Some(VehicleCategory.Car) if isShared   => Carsharing
      case Some(VehicleCategory.Car)               => Cars
      case _ if isFreight                          => Freight
      case _                                       => Transit
    }
  }
}
