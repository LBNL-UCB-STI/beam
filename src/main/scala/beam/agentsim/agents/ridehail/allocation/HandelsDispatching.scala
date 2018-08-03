package beam.agentsim.agents.ridehail.allocation

trait HandelsDispatching {

  def proposeVehicleAllocation(
    vehicleAllocationRequest: VehicleAllocationRequest
  ): Option[VehicleAllocation]

}
