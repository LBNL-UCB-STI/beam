package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.{RefuelSource, VehicleId}
import beam.agentsim.agents.ridehail.RideHailManagerHelper.RideHailAgentLocation
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.{ParkingEvent, SpaceTime}
import beam.agentsim.infrastructure.ChargingNetworkManager.{
  ChargingPlugRequest,
  ChargingUnplugRequest,
  StartingRefuelSession,
  UnhandledVehicle,
  UnpluggingVehicle,
  WaitingToCharge
}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.Location
import beam.sim.Geofence
import beam.sim.config.BeamConfig
import beam.utils.logging.LogActorState
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

  // TODO: KEEPING THIS OLD CODE FOR NOW FOR REFERENCE
//
//  def hasHighSocAndZoneIsDCFast(beamVehicle: BeamVehicle, parkingZone: ParkingZone[GEO]): Boolean = {
//    val soc = beamVehicle.primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelCapacityInJoule
//    soc >= 0.8 && parkingZone.chargingPointType
//      .map(_.asInstanceOf[CustomChargingPoint].installedCapacity > 20.0)
//      .getOrElse(false)
//  }

//  /**
//    * Gets the location in UTM for a parking zone.
//    *
//    * @param parkingZoneId ID of the parking zone
//    * @return Parking zone location in UTM.
//    */
//  def getParkingZoneLocationUtm(parkingZoneId: Int): Coord = {
//    val geoId = rideHailParkingZones(parkingZoneId).geoId
//    val geo = idToGeoMapping(geoId)
//    import GeoLevel.ops._
//    geo.centroidLocation
//  }

  /**
    * Makes an attempt to "claim" the parking stall passed in as an argument, or optionally a stall from a different
    * ParkingZone in the same Depot depending on the [[StallAssignmentStrategy]] selected. If the assigned stall is
    * available, then the vehicle will be added to internal tracking as a charging vehicle and a
    * [[StartRefuelSessionTrigger]] will be returned. If all parking stalls of the same type in the associated depot are
    * in use, then this vehicle will be added to a queue to await charging later and an empty trigger vector will be
    * returned.
    *
    * @param beamVehicle
    * @param originalParkingStallFoundDuringAssignment
    * @param tick
    * @param vehicleQueuePriority
    * @param source
    * @return vector of [[ScheduleTrigger]] objects
    */
  def attemptToRefuel(
    beamVehicle: BeamVehicle,
    originalParkingStallFoundDuringAssignment: ParkingStall,
    tick: Int,
    source: RefuelSource,
    triggerId: Long
  ): Future[Vector[ScheduleTrigger]] = {
    findAndClaimStallAtDepot(tick, beamVehicle, originalParkingStallFoundDuringAssignment, triggerId).map {
      case _: StartingRefuelSession =>
        beamVehicle.useParkingStall(originalParkingStallFoundDuringAssignment)
        if (addVehicleToChargingInDepotUsing(originalParkingStallFoundDuringAssignment, beamVehicle, tick, source)) {
          Vector()
        } else {
          Vector()
        }
      case _ @WaitingToCharge(_, _, numVehicleWaitingToCharge, _) =>
        addVehicleAndStallToRefuelingQueueFor(
          beamVehicle,
          originalParkingStallFoundDuringAssignment,
          -numVehicleWaitingToCharge,
          source
        )
        Vector()
      case e if beamVehicle.isEV =>
        log.error(s"Not expecting this response: $e")
        Vector()
      case e =>
        log.debug(s"Non Electric RH attempting to refuel: $e")
        Vector()
    }
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

// TODO: KEEPING THIS OLD CODE FOR NOW FOR REFERENCE

  /**
    * Looks up the ParkingZone associated with the parkingStall argument and claims a stall from that Zone if there
    * are any available, returning the stall as an output. Otherwise, if no stalls are available returns None.
    *
    * @param parkingStall the parking stall to claim
    * @return an optional parking stall that was assigned or None on failure
    */
  def findAndClaimStallAtDepot(
    tick: Int,
    beamVehicle: BeamVehicle,
    parkingStall: ParkingStall,
    triggerId: Long
  ): Future[Any] = {
    eventsManager.processEvent(
      ParkingEvent(tick, parkingStall, beamServices.geo.utm2Wgs(parkingStall.locationUTM), beamVehicle.id, id.toString)
    )
    log.debug("Refuel started at {}, triggerId: {}, vehicle id: {}", tick, triggerId, beamVehicle.id)
    parkingStall.chargingPointType match {
      case Some(_) if beamVehicle.isEV =>
        log.debug(s"Refueling sending ChargingPlugRequest for ${beamVehicle.id} and $triggerId")
        chargingNetworkManager ? ChargingPlugRequest(tick, beamVehicle, parkingStall, Id.createPersonId(id), triggerId)
      case _ =>
        log.debug("This is not an EV {} that needs to charge at stall {}", beamVehicle.id, parkingStall.parkingZoneId)
        Future.successful(())
    }
  }

  /**
    * Adds a vehicle to internal data structures to track that it is engaged in a charging session.
    *
    * @param stall
    * @param beamVehicle
    * @param tick
    * @param source Tag used for logging purposes.
    */
  def addVehicleToChargingInDepotUsing(
    stall: ParkingStall,
    beamVehicle: BeamVehicle,
    tick: Int,
    source: RefuelSource
  ): Boolean = {
    if (chargingVehicleToParkingStallMap.keys.exists(_ == beamVehicle.id)) {
      log.warning(
        "{} is already charging in {}, yet it is being added to {}. Source: {} THIS SHOULD NOT HAPPEN!",
        beamVehicle.id,
        chargingVehicleToParkingStallMap(beamVehicle.id),
        stall,
        source
      )
      beamVehicle.getDriver.get ! LogActorState
      false
    } else {
      log.debug(
        "Cache that vehicle {} is now charging in depot {}, source {}",
        beamVehicle.id,
        stall.parkingZoneId,
        source
      )
      chargingVehicleToParkingStallMap += beamVehicle.id -> stall
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.add(beamVehicle.id)
      val (chargingSessionDuration, _) = beamVehicle.refuelingSessionDurationAndEnergyInJoules(None, None, None)
      putNewTickAndObservation(beamVehicle.id, (tick, s"Charging(${source})"))
      vehicleIdToEndRefuelTick.put(beamVehicle.id, tick + chargingSessionDuration)
      true
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
  def removeFromCharging(vehicleId: VehicleId, tick: Int, triggerId: Long): Option[ParkingStall] = {
    vehicleIdToEndRefuelTick.remove(vehicleId)
    val stallOpt = chargingVehicleToParkingStallMap.remove(vehicleId)
    stallOpt.foreach { stall =>
      log.debug("Remove from cache that vehicle {} was charging in stall {}", vehicleId, stall)
      putNewTickAndObservation(vehicleId, (tick, "RemoveFromCharging"))
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.remove(vehicleId)
      releaseStall(stall, tick, resources(vehicleId), triggerId)
    }
    stallOpt
  }

  // TODO: KEEPING THIS OLD CODE FOR NOW FOR REFERENCE

  /**
    * releases a single stall in use at this Depot
    *
    * @param parkingStall stall we want to release
    * @return Boolean if zone is defined and remove was success
    */
  private def releaseStall(
    parkingStall: ParkingStall,
    tick: Int,
    beamVehicle: BeamVehicle,
    triggerId: Long
  ): Future[Any] = {
    if (parkingStall.chargingPointType.isEmpty) {
      Future.successful(()).map { _ =>
        ParkingNetworkManager.handleReleasingParkingSpot(
          tick,
          beamVehicle,
          None,
          this.id,
          parkingManager,
          beamServices.matsimServices.getEvents,
          triggerId
        )
      }
    } else {
      (chargingNetworkManager ? ChargingUnplugRequest(
        tick,
        this.id,
        beamVehicle,
        triggerId
      )).map {
        case _ @UnpluggingVehicle(_, personId, _, energyCharged, _) =>
          ParkingNetworkManager.handleReleasingParkingSpot(
            tick,
            beamVehicle,
            Some(energyCharged),
            personId,
            parkingManager,
            beamServices.matsimServices.getEvents,
            triggerId
          )
        case _: UnhandledVehicle =>
          ParkingNetworkManager.handleReleasingParkingSpot(
            tick,
            beamVehicle,
            None,
            this.id,
            parkingManager,
            beamServices.matsimServices.getEvents,
            triggerId
          )
        case e =>
          log.error(s"Not expecting this response: $e")
      }
    }
  }

  /**
    * Adds the vehicle to the appropriate queue for the depot and [[ChargingPlugType]] associatd with the parkingStall argument.
    *
    * @param vehicle
    * @param parkingStall
    * @param source used for logging purposes only.
    */
  def addVehicleAndStallToRefuelingQueueFor(
    vehicle: BeamVehicle,
    parkingStall: ParkingStall,
    priority: Double,
    source: RefuelSource
  ): Unit = {
    val chargingQueue = parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId).chargingQueue
    val chargingQueueEntry = ChargingQueueEntry(vehicle, parkingStall, priority)
    if (chargingQueue.exists(_.beamVehicle.id == vehicle.id)) {
      log.warning(
        "{} already exists in parking zone {} queue. Not re-adding as it is a duplicate. Source: {} " +
        "THIS SHOULD NEVER HAPPEN!",
        vehicle.id,
        parkingStall.parkingZoneId,
        source
      )
    } else {
      log.debug(
        "Add vehicle {} to charging queue of length {} at depot {}",
        vehicle.id,
        chargingQueue.size,
        parkingStall.parkingZoneId
      )
      putNewTickAndObservation(vehicle.id, (vehicle.spaceTime.time, s"EnQueue(${source})"))
      vehiclesInQueueToParkingZoneId.put(vehicle.id, parkingStall.parkingZoneId)
      chargingQueue.enqueue(chargingQueueEntry)
    }
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

  // TODO: KEEPING THIS OLD CODE FOR NOW FOR REFERENCE

//  /**
//    * Get all vehicles that are on the way to the refueling depot specified by the [[ParkingZone]] id.
//    *
//    * @param parkingZoneId
//    * @return a [[Vector]] of [[VechicleId]]s
//    */
//  def getVehiclesOnWayToRefuelingDepot(parkingZoneId: Int): Vector[VehicleId] =
//    parkingZoneIdToParkingZoneDepotData(parkingZoneId).vehiclesOnWayToDepot.toVector

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

  /**
    * *
    * @param tick
    * @param vehiclesWithoutCustomVehicles
    * @param triggerId
    * @return
    */
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
          .map { response =>
            beamVehicle.setReservedParkingStall(Some(response.stall))
            (vehicleId, response.stall)
          }
      })
  }

  /**
    * *
    * @param whenWhere
    * @param beamVehicle
    * @param triggerId
    * @return
    */
  def sendChargingInquiry(whenWhere: SpaceTime, beamVehicle: BeamVehicle, triggerId: Long): Future[Any] = {
    val inquiry = ParkingInquiry.init(
      whenWhere,
      "wherever",
      VehicleManager.getReservedFor(beamVehicle.vehicleManagerId.get).get,
      Some(beamVehicle),
      valueOfTime = rideHailConfig.cav.valueOfTime,
      reserveStall = false,
      searchMode = ParkingSearchMode.DestinationCharging,
      triggerId = triggerId
    )
    chargingNetworkManager ? inquiry
  }
}
