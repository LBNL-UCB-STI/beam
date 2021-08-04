package beam.agentsim.agents.ridehail

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.parking.{GeoLevel, ParkingZone, ParkingZoneId}
import beam.agentsim.infrastructure.{ChargingNetwork, ParkingStall}
import beam.sim.{BeamServices, Geofence}
import org.matsim.api.core.v01.Id

import scala.collection.mutable

abstract class RideHailDepotParkingManager[GEO: GeoLevel](
  vehicleManagerId: Id[VehicleManager],
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]]
) extends ChargingNetwork[GEO](vehicleManagerId, parkingZones) {

  override def processReleaseParkingStall(release: ReleaseParkingStall): Boolean = {
    if (!parkingZones.contains(release.stall.parkingZoneId)) {
      false
    } else {
      val parkingZone: ParkingZone[GEO] = parkingZones(release.stall.parkingZoneId)
      val success = ParkingZone.releaseStall(parkingZone)
      if (success) {
        totalStallsInUse -= 1
        totalStallsAvailable += 1
      }
      success
    }
  }

  def findStationsForVehiclesInNeedOfCharging(
    tick: Int,
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    idleVehicles: collection.Map[Id[BeamVehicle], RideHailManagerHelper.RideHailAgentLocation],
    beamServices: BeamServices
  ): Vector[(Id[BeamVehicle], ParkingStall)]

  /**
    * Notify this [[RideHailDepotParkingManager]] that a vehicles is no longer on the way to the depot.
    *
    * @param vehicleId
    * @return the optional [[ParkingStall]] of the vehicle if it was found in the internal tracking, None if
    *         the vehicle was not found.
    */
  def notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId: VehicleId): Option[ParkingStall]

  /**
    * This vehicle is no longer charging and should be removed from internal tracking data.
    *
    * @param vehicle
    * @return the stall if found and successfully removed
    */
  def removeFromCharging(vehicle: VehicleId, tick: Int): Option[ParkingStall]

  /**
    * Given a parkingZoneId, dequeue the next vehicle that is waiting to charge.
    *
    * @param parkingZoneId
    * @param tick
    * @return
    */
  def dequeueNextVehicleForRefuelingFrom(
    parkingZoneId: Id[ParkingZoneId],
    tick: Int
  ): Option[ChargingQueueEntry]

  /**
    * Give depot manager opportunity to internalize geofences associated with fleet.
    *
    * @param vehicleIdToGeofenceMap
    */
  def registerGeofences(vehicleIdToGeofenceMap: mutable.Map[VehicleId, Option[Geofence]])

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge or actively charging?
    *
    * @param vehicleId
    * @return
    */
  def isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(vehicleId: VehicleId): Boolean

  /**
    * Notify this [[RideHailDepotParkingManager]] that vehicles are on the way to the depot for the purpose of refueling.
    *
    * @param newVehiclesHeadedToDepot
    */
  def notifyVehiclesOnWayToRefuelingDepot(newVehiclesHeadedToDepot: Vector[(VehicleId, ParkingStall)]): Unit

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId
    * @return
    */
  def isOnWayToRefuelingDepot(vehicleId: VehicleId): Boolean
}

trait FindDepotAttributes {}

case class ParkingZoneDepotData(
  vehiclesOnWayToDepot: mutable.Set[VehicleId],
  chargingVehicles: mutable.Set[VehicleId],
  chargingQueue: mutable.PriorityQueue[ChargingQueueEntry],
  numPhantomVehiclesCharging: Int,
  numPhantomVehiclesQueued: Int,
  serviceTimeOfQueuedPhantomVehicles: Int
)

object ParkingZoneDepotData {
  case class ChargingQueueEntry(beamVehicle: BeamVehicle, parkingStall: ParkingStall, priority: Double)

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
