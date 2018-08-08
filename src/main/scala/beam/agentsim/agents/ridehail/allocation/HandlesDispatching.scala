package beam.agentsim.agents.ridehail.allocation

trait HandlesDispatching {

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation]

}
