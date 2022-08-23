package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.DefaultRideHailDepotParkingManager.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId

import scala.collection.mutable

case class ParkingZoneDepotData(
  vehiclesOnWayToDepot: mutable.Set[VehicleId],
  chargingVehicles: mutable.Set[VehicleId],
  chargingQueue: mutable.PriorityQueue[ChargingQueueEntry],
  numPhantomVehiclesCharging: Int,
  numPhantomVehiclesQueued: Int,
  serviceTimeOfQueuedPhantomVehicles: Int
)

object ParkingZoneDepotData {

  def empty: ParkingZoneDepotData =
    ParkingZoneDepotData(
      mutable.Set.empty[VehicleId],
      mutable.Set.empty[VehicleId],
      mutable.PriorityQueue.empty[ChargingQueueEntry](Ordering.by[ChargingQueueEntry, Double](_.priority)),
      0,
      0,
      0
    )
}
