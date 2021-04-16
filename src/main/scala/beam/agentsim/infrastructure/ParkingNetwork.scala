package beam.agentsim.infrastructure

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager, VehicleManagerType}
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

trait ParkingNetwork extends LazyLogging {

  val vehicleManagerId: Id[VehicleManager]

  def processParkingInquiry(
    inquiry: ParkingInquiry,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): Option[ParkingInquiryResponse]

  def processReleaseParkingStall(release: ReleaseParkingStall)
}

object ParkingNetwork {

  def getVehicleManagerIdForParking(
    vehicle: BeamVehicle,
    vehicleManagers: Map[Id[VehicleManager], VehicleManager]
  ): Id[VehicleManager] = {
    vehicleManagers(vehicle.managerId).managerType match {
      case VehicleManagerType.Ridehail   => VehicleManager.privateVehicleManager.managerId
      case VehicleManagerType.Carsharing => VehicleManager.privateVehicleManager.managerId
      case _                             => vehicle.managerId
    }
  }
}
