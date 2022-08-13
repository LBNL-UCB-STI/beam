package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.Geofence
import beam.sim.config.BeamConfig
import beam.utils.logging.pattern.ask
import org.matsim.api.core.v01.Id

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Manages the parking/charging depots for the RideHailManager. Depots can contain heterogeneous [[ChargingPlugTypes]]
  * and any queues for those charger types are tracked separately.
  *
  * A Depot is a collection of ParkingZones... each zone represents the combination of a location (i.e. a TAZ) and a ChargingPointType
  * along with the queue for that charging point type. Queues are managed at the level of a ParkingZone.
  *
  * Contains an MNL depot / chargingPlugType selection algorithm that takes into account travel time to the depot (using Skims.od_skimmer),
  * time spent in queue waiting for a charger, time spent charging the vehicle, and vehicle range. This is a probablistic selection algorithm, so if two
  * alternatives yield equivalent utility during evaluation, then half of vehicles will be dispatched to each alternative. To decrease
  * the degree of stochasticity, increase the magnitude of the three MNL params (below) which will decrease the overall elasticity of the
  * MNL function.
  *
  * Key parameters to control behavior of this class:
  *
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.multinomialLogit.params.drivingTimeMultiplier = "double | -0.01666667" // one minute of driving is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.multinomialLogit.params.queueingTimeMultiplier = "double | -0.01666667" // one minute of queueing is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.multinomialLogit.params.chargingTimeMultiplier = "double | -0.01666667" // one minute of charging is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.multinomialLogit.params.insufficientRangeMultiplier = "double | -60.0" // 60 minute penalty if out of range
  */
trait DefaultRideHailDepotParkingManager extends {
  this: RideHailManager =>

  val outputRidehailParkingFileName = "ridehailParking.csv"
  val rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail = beamServices.beamConfig.beam.agentsim.agents.rideHail
  val depots: Map[Id[ParkingZoneId], ParkingZone] = rideHailChargingNetwork.parkingZones

  /*
   * All internal data to track Depots, ParkingZones, and charging queues are kept in ParkingZoneDepotData which is
   * accessible via a Map on the ParkingZoneId
   */
  protected val parkingZoneIdToParkingZoneDepotData: mutable.Map[Id[ParkingZoneId], ParkingZoneDepotData] =
    mutable.Map.empty[Id[ParkingZoneId], ParkingZoneDepotData]

  depots.foreach { case (parkingZoneId, _) =>
    parkingZoneIdToParkingZoneDepotData.put(parkingZoneId, ParkingZoneDepotData.empty)
  }

  ParkingZoneFileUtils.toCsv(
    rideHailChargingNetwork.parkingZones,
    beamServices.matsimServices.getControlerIO.getOutputFilename(outputRidehailParkingFileName)
  )

  /*
   *  Maps from VehicleId -> XX
   */
  private val chargingVehicleToParkingStallMap: mutable.Map[VehicleId, ParkingStall] =
    mutable.Map.empty[VehicleId, ParkingStall]
  private val vehiclesOnWayToDepot: mutable.Map[VehicleId, ParkingStall] = mutable.Map.empty[VehicleId, ParkingStall]
  private val vehicleIdToEndRefuelTick: mutable.Map[VehicleId, Int] = mutable.Map.empty[VehicleId, Int]

  private val vehiclesInQueueToParkingZoneId: mutable.Map[VehicleId, Id[ParkingZoneId]] =
    mutable.Map.empty[VehicleId, Id[ParkingZoneId]]

  private val vehicleIdToLastObservedTickAndAction: mutable.Map[VehicleId, mutable.ListBuffer[(Int, String)]] =
    mutable.Map.empty[VehicleId, mutable.ListBuffer[(Int, String)]]
  private val vehicleIdToGeofence: mutable.Map[VehicleId, Geofence] = mutable.Map.empty[VehicleId, Geofence]

  /*
   * Track "Depots" as a mapping from TAZ Id to ParkingZones to facilitate processing all ParkingZones in a depot
   */
  val tazIdToParkingZones: mutable.Map[Id[TAZ], Map[Id[ParkingZoneId], ParkingZone]] =
    mutable.Map.empty[Id[TAZ], Map[Id[ParkingZoneId], ParkingZone]]

  depots.groupBy(_._2.tazId).foreach(tup => tazIdToParkingZones += tup)

  def registerGeofences(vehicleIdToGeofenceMap: mutable.Map[VehicleId, Option[Geofence]]) = {
    vehicleIdToGeofenceMap.foreach {
      case (vehicleId, Some(geofence)) =>
        vehicleIdToGeofence.put(vehicleId, geofence)
      case (_, _) =>
    }
  }

  def findChargingStalls(
    tick: Int,
    vehiclesWithoutCustomVehicles: Map[Id[BeamVehicle], RideHailAgentLocation],
    triggerId: Long
  ): Future[Vector[(Id[BeamVehicle], ParkingStall)]] = {
    val idleVehicleIdsWantingToRefuelWithLocation = vehiclesWithoutCustomVehicles.toVector.filter {
      case (vehicleId: Id[BeamVehicle], _) =>
        resources.get(vehicleId) match {
          case Some(beamVehicle) if beamVehicle.isCAV =>
            beamVehicle.isRefuelNeeded(
              rideHailConfig.cav.refuelRequiredThresholdInMeters,
              rideHailConfig.cav.noRefuelThresholdInMeters
            )
          case _ => false
        }
    }
    Future
      .sequence(idleVehicleIdsWantingToRefuelWithLocation.map { case (vehicleId, rideHailAgentLocation) =>
        val beamVehicle = resources(vehicleId)
        val locationUtm: Location = rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices)
        sendChargingInquiry(SpaceTime(locationUtm, tick), beamVehicle, triggerId)
          .mapTo[ParkingInquiryResponse]
          .map(x => (vehicleId, x.stall))
      })
  }

  def sendChargingInquiry(whenWhere: SpaceTime, beamVehicle: BeamVehicle, triggerId: Long): Future[Any] = {
    val inquiry = ParkingInquiry.init(
      whenWhere,
      "wherever",
      VehicleManager.getReservedFor(beamVehicle.vehicleManagerId.get).get,
      Some(beamVehicle),
      valueOfTime = rideHailConfig.cav.valueOfTime,
      triggerId = triggerId,
      searchMode = ParkingSearchMode.DestinationCharging
    )
    chargingNetworkManager ? inquiry
  }

  /**
    * Given a parkingZoneId, dequeue the next vehicle that is waiting to charge.
    *
    * @param parkingZoneId
    * @return optional tuple with [[BeamVehicle]] and [[ParkingStall]]
    */
  def dequeueNextVehicleForRefuelingFrom(
    parkingZoneId: Id[ParkingZoneId],
    tick: Int
  ): Option[ChargingQueueEntry] = {
    val chargingQueue = parkingZoneIdToParkingZoneDepotData(parkingZoneId).chargingQueue
    if (chargingQueue.isEmpty) {
      None
    } else {
      val ChargingQueueEntry(beamVehicle, parkingStall, _) = chargingQueue.dequeue
      log.debug("Dequeueing vehicle {} to charge at depot {}", beamVehicle, parkingStall.parkingZoneId)
      putNewTickAndObservation(beamVehicle.id, (tick, "DequeueToCharge"))
      vehiclesInQueueToParkingZoneId.remove(beamVehicle.id)
      Some(ChargingQueueEntry(beamVehicle, parkingStall, 1.0))
    }
  }

  /**
    * Store the last tick and action observed by this vehicle. For debugging purposes.
    *
    * @param vehicleId VehicleId
    * @param tickAndAction a tuple with the tick and action label (String) to store
    * @return
    */
  def putNewTickAndObservation(vehicleId: VehicleId, tickAndAction: (Int, String)) = {
    vehicleIdToLastObservedTickAndAction.get(vehicleId) match {
      case Some(listBuffer) =>
        listBuffer.append(tickAndAction)
      case None =>
        val listBuffer = new ListBuffer[(Int, String)]()
        listBuffer.append(tickAndAction)
        vehicleIdToLastObservedTickAndAction.put(vehicleId, listBuffer)
    }
  }

  /**
    * This vehicle is no longer charging and should be removed from internal tracking data.
    *
    * @param vehicle Beam Vehicle ID
    * @return the stall if found and successfully removed
    */
  def removeFromCharging(vehicle: VehicleId, tick: Int): Option[ParkingStall] = {
    vehicleIdToEndRefuelTick.remove(vehicle)
    val stallOpt = chargingVehicleToParkingStallMap.remove(vehicle)
    stallOpt.foreach { stall =>
      log.debug("Remove from cache that vehicle {} was charging in stall {}", vehicle, stall)
      putNewTickAndObservation(vehicle, (tick, "RemoveFromCharging"))
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.remove(vehicle)
    }
//    ParkingNetworkManager.handleReleasingParkingSpot(
//      tick,
//      vehicle,
//      Some(energyCharged),
//      personId,
//      getParkingManager,
//      getBeamServices.matsimServices.getEvents,
//      triggerId
//    )
    stallOpt
  }

  /**
    * Notify this [[RideHailDepotParkingManager]] that vehicles are on the way to the depot for the purpose of refueling.
    *
    * @param newVehiclesHeadedToDepot
    */
  def notifyVehiclesOnWayToRefuelingDepot(newVehiclesHeadedToDepot: Vector[(VehicleId, ParkingStall)]): Unit = {
    newVehiclesHeadedToDepot.foreach { case (vehicleId, parkingStall) =>
      log.debug("Vehicle {} headed to depot depot {}", vehicleId, parkingStall.parkingZoneId)
      vehiclesOnWayToDepot.put(vehicleId, parkingStall)
      val parkingZoneDepotData = parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId)
      parkingZoneDepotData.vehiclesOnWayToDepot.add(vehicleId)
    }
  }

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId Beam Vehicle ID
    * @return
    */
  def isOnWayToRefuelingDepot(vehicleId: VehicleId): Boolean = vehiclesOnWayToDepot.contains(vehicleId)

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge or actively charging?
    *
    * @param vehicleId
    * @return
    */
  def isOnWayToRefuelingDepotOrIsRefuelingOrInQueue(vehicleId: VehicleId): Boolean =
    vehiclesOnWayToDepot.contains(vehicleId) || chargingVehicleToParkingStallMap.contains(
      vehicleId
    ) || vehiclesInQueueToParkingZoneId
      .contains(vehicleId)

  /**
    * Notify this [[RideHailDepotParkingManager]] that a vehicles is no longer on the way to the depot.
    *
    * @param vehicleId
    * @return the optional [[ParkingStall]] of the vehicle if it was found in the internal tracking, None if
    *         the vehicle was not found.
    */
  def notifyVehicleNoLongerOnWayToRefuelingDepot(vehicleId: VehicleId): Option[ParkingStall] = {
    val parkingStallOpt = vehiclesOnWayToDepot.remove(vehicleId)
    parkingStallOpt match {
      case Some(parkingStall) =>
        parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId).vehiclesOnWayToDepot.remove(vehicleId)
      case None =>
    }
    parkingStallOpt
  }
}
