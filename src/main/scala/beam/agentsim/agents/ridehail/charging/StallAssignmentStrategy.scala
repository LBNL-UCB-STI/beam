package beam.agentsim.agents.ridehail.charging

import beam.agentsim.agents.ridehail.RideHailDepotParkingManager
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.parking.ParkingZone

trait StallAssignmentStrategy {

  def execute[GEO](
    originalParkingZone: ParkingZone[GEO],
    parkingStall: ParkingStall,
    tick: Int,
    beamVehicle: BeamVehicle,
    rideHailDepotParkingManager: RideHailDepotParkingManager[GEO]
  ): ParkingZone[GEO]
}

class SeparateParkingZoneStallAssignmentStrategy extends StallAssignmentStrategy {
  override def execute[GEO](
    originalParkingZone: ParkingZone[GEO],
    parkingStall: ParkingStall,
    tick: Int,
    beamVehicle: BeamVehicle,
    rideHailDepotParkingManager: RideHailDepotParkingManager[GEO]
  ): ParkingZone[GEO] = originalParkingZone
}
