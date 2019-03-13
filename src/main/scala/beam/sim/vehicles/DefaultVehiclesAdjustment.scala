package beam.sim.vehicles
import beam.agentsim.agents.vehicles.{BeamVehicleType, VehicleCategory}
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id

case class DefaultVehiclesAdjustment(beamServices: BeamServices) extends VehiclesAdjustment {
  val carId: Id[BeamVehicleType] = Id.create("Car", classOf[BeamVehicleType])
  val vehicleTypesByCategory: BeamVehicleType = beamServices.vehicleTypes.values.find(vt => vt.id == carId).get

  override def sampleRideHailVehicleTypes(
    numVehicles: Int,
    vehicleCategory: VehicleCategory
  ): List[BeamVehicleType] = {
    Range(0, numVehicles).map { i =>
      if (vehicleCategory == VehicleCategory.Car) {
        vehicleTypesByCategory
      } else throw new NotImplementedError(vehicleCategory.toString)
    }.toList
  }

  override def sampleVehicleTypesForHousehold(
    numVehicles: Int,
    vehicleCategory: VehicleCategory
  ): List[BeamVehicleType] = {
    Range(0, numVehicles).map { _ =>
      if (vehicleCategory == VehicleCategory.Car) {
        vehicleTypesByCategory
      } else throw new NotImplementedError(vehicleCategory.toString)
    }.toList
  }

}
