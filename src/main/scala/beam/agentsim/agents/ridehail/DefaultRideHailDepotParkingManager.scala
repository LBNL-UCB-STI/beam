package beam.agentsim.agents.ridehail

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.{RefuelSource, VehicleId}
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, Geofence}
import beam.utils.logging.LogActorState
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

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
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.drivingTimeMultiplier = "double | -0.01666667" // one minute of driving is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.queueingTimeMultiplier = "double | -0.01666667" // one minute of queueing is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.chargingTimeMultiplier = "double | -0.01666667" // one minute of charging is one util
  * beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.insufficientRangeMultiplier = "double | -60.0" // 60 minute penalty if out of range
  */
class DefaultRideHailDepotParkingManager[GEO: GeoLevel](
  vehicleManagerId: Id[VehicleManager],
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  outputDirectory: OutputDirectoryHierarchy,
  rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail
) extends RideHailDepotParkingManager[GEO](vehicleManagerId, parkingZones) {

  /*
   * All internal data to track Depots, ParkingZones, and charging queues are kept in ParkingZoneDepotData which is
   * accessible via a Map on the ParkingZoneId
   */
  protected val parkingZoneIdToParkingZoneDepotData: mutable.Map[Id[ParkingZoneId], ParkingZoneDepotData] =
    mutable.Map.empty[Id[ParkingZoneId], ParkingZoneDepotData]
  parkingZones.foreach { case (parkingZoneId, _) =>
    parkingZoneIdToParkingZoneDepotData.put(parkingZoneId, ParkingZoneDepotData.empty)
  }

  override protected val searchFunctions: Option[InfrastructureFunctions[_]] = None

  protected val chargingPlugTypesSortedByPower = parkingZones
    .flatMap(_._2.chargingPointType)
    .toArray
    .distinct
    .sortBy(-ChargingPointType.getChargingPointInstalledPowerInKw(_))

  ParkingZoneFileUtils.toCsv(
    parkingZones,
    outputDirectory.getOutputFilename(DefaultRideHailDepotParkingManager.outputRidehailParkingFileName)
  )

  def fastestChargingPlugType: ChargingPointType = chargingPlugTypesSortedByPower.head

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
  val tazIdToParkingZones: mutable.Map[Id[GEO], Map[Id[ParkingZoneId], ParkingZone[GEO]]] =
    mutable.Map.empty[Id[GEO], Map[Id[ParkingZoneId], ParkingZone[GEO]]]
  parkingZones.groupBy(_._2.geoId).foreach(tup => tazIdToParkingZones += tup)

  def registerGeofences(vehicleIdToGeofenceMap: mutable.Map[VehicleId, Option[Geofence]]) = {
    vehicleIdToGeofenceMap.foreach {
      case (vehicleId, Some(geofence)) =>
        vehicleIdToGeofence.put(vehicleId, geofence)
      case (_, _) =>
    }
  }

  override def findStationsForVehiclesInNeedOfCharging(
    tick: Int,
    resources: mutable.Map[Id[BeamVehicle], BeamVehicle],
    idleVehicles: collection.Map[Id[BeamVehicle], RideHailManagerHelper.RideHailAgentLocation],
    beamServices: BeamServices
  ): Vector[(Id[BeamVehicle], ParkingStall)] = {
    val idleVehicleIdsWantingToRefuelWithLocation = idleVehicles.toVector.filter {
      case (vehicleId: Id[BeamVehicle], _) => {
        resources.get(vehicleId) match {
          case Some(beamVehicle) if beamVehicle.isCAV => {
            beamVehicle.isRefuelNeeded(
              rideHailConfig.cav.refuelRequiredThresholdInMeters,
              rideHailConfig.cav.noRefuelThresholdInMeters
            )
          }
          case _ => false
        }
      }
    }

    idleVehicleIdsWantingToRefuelWithLocation.map { case (vehicleId, rideHailAgentLocation) =>
      val beamVehicle = resources(vehicleId)
      val locationUtm: Location = rideHailAgentLocation.getCurrentLocationUTM(tick, beamServices)
      val parkingStall =
        processParkingInquiry(
          ParkingInquiry.init(
            SpaceTime(locationUtm, tick),
            "wherever",
            vehicleManagerId,
            Some(beamVehicle),
            valueOfTime = rideHailConfig.cav.valueOfTime,
            triggerId = 0
          )
        ).map(_.stall).getOrElse(throw new IllegalStateException(s"no parkingStall available for $vehicleId"))
      (vehicleId, parkingStall)
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
      logger.debug("Dequeueing vehicle {} to charge at depot {}", beamVehicle, parkingStall.parkingZoneId)
      putNewTickAndObservation(beamVehicle.id, (tick, "DequeueToCharge"))
      vehiclesInQueueToParkingZoneId.remove(beamVehicle.id)
      Some(ChargingQueueEntry(beamVehicle, parkingStall, 1.0))
    }
  }

  /**
    * Adds a vehicle to internal data structures to track that it is engaged in a charging session.
    *
    * @param stall ParkingStall
    * @param beamVehicle BeamVehicle
    * @param tick Int
    * @param source Tag used for logging purposes.
    */
  def addVehicleToChargingInDepotUsing(
    stall: ParkingStall,
    beamVehicle: BeamVehicle,
    tick: Int,
    source: RefuelSource
  ): Boolean = {
    if (chargingVehicleToParkingStallMap.keys.exists(_ == beamVehicle.id)) {
      logger.warn(
        "{} is already charging in {}, yet it is being added to {}. Source: {} THIS SHOULD NOT HAPPEN!",
        beamVehicle.id,
        chargingVehicleToParkingStallMap(beamVehicle.id),
        stall,
        source
      )
      beamVehicle.getDriver.get ! LogActorState
      false
    } else {
      logger.debug(
        "Cache that vehicle {} is now charging in depot {}, source {}",
        beamVehicle.id,
        stall.parkingZoneId,
        source
      )
      chargingVehicleToParkingStallMap += beamVehicle.id -> stall
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.add(beamVehicle.id)
      val (chargingSessionDuration, _) = beamVehicle.refuelingSessionDurationAndEnergyInJoules(None, None, None)
      putNewTickAndObservation(beamVehicle.id, (tick, s"Charging($source)"))
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
    * @param vehicle
    * @return the stall if found and successfully removed
    */
  def removeFromCharging(vehicle: VehicleId, tick: Int): Option[ParkingStall] = {
    vehicleIdToEndRefuelTick.remove(vehicle)
    val stallOpt = chargingVehicleToParkingStallMap.remove(vehicle)
    stallOpt.foreach { stall =>
      logger.debug("Remove from cache that vehicle {} was charging in stall {}", vehicle, stall)
      putNewTickAndObservation(vehicle, (tick, "RemoveFromCharging"))
      parkingZoneIdToParkingZoneDepotData(stall.parkingZoneId).chargingVehicles.remove(vehicle)
      processReleaseParkingStall(ReleaseParkingStall(stall, 0))
    }
    stallOpt
  }

  /**
    * Adds the vehicle to the appropriate queue for the depot and [[ChargingPlugType]] associatd with the parkingStall argument.
    *
    * @param vehicle BeamVehicle
    * @param parkingStall ParkingStall
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
      logger.warn(
        "{} already exists in parking zone {} queue. Not re-adding as it is a duplicate. Source: {} " +
        "THIS SHOULD NEVER HAPPEN!",
        vehicle.id,
        parkingStall.parkingZoneId,
        source
      )
    } else {
      logger.debug(
        "Add vehicle {} to charging queue of length {} at depot {}",
        vehicle.id,
        chargingQueue.size,
        parkingStall.parkingZoneId
      )
      putNewTickAndObservation(vehicle.id, (vehicle.spaceTime.time, s"EnQueue($source)"))
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
      logger.debug("Vehicle {} headed to depot depot {}", vehicleId, parkingStall.parkingZoneId)
      vehiclesOnWayToDepot.put(vehicleId, parkingStall)
      val parkingZoneDepotData = parkingZoneIdToParkingZoneDepotData(parkingStall.parkingZoneId)
      parkingZoneDepotData.vehiclesOnWayToDepot.add(vehicleId)
    }
  }

  /**
    * Is the [[vehicleId]] currently on the way to a refueling depot to charge?
    *
    * @param vehicleId
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
    * Get all vehicles that are on the way to the refueling depot specified by the [[ParkingZone]] id.
    * @param parkingZoneId
    * @return a [[Vector]] of [[VechicleId]]s
    */
  def getVehiclesOnWayToRefuelingDepot(parkingZoneId: Id[ParkingZoneId]): Vector[VehicleId] =
    parkingZoneIdToParkingZoneDepotData(parkingZoneId).vehiclesOnWayToDepot.toVector

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

object DefaultRideHailDepotParkingManager {

  // a ride hail agent is searching for a charging depot and is not in service of an activity.
  // for this reason, a higher max radius is reasonable.
  val SearchStartRadius: Double = 40000.0 // meters
  val SearchMaxRadius: Int = 80465 // 50 miles, in meters
  val outputRidehailParkingFileName = "ridehailParking.csv"

  def apply[GEO: GeoLevel](
    vehicleManagerId: Id[VehicleManager],
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    boundingBox: Envelope,
    beamServices: BeamServices
  ): RideHailDepotParkingManager[GEO] = {
    new DefaultRideHailDepotParkingManager[GEO](
      vehicleManagerId,
      parkingZones,
      beamServices.matsimServices.getControlerIO,
      beamServices.beamConfig.beam.agentsim.agents.rideHail
    ) {
      override val searchFunctions: Option[InfrastructureFunctions[_]] = Some(
        new DefaultRidehailFunctions(
          geoQuadTree,
          idToGeoMapping,
          geoToTAZ,
          parkingZones,
          parkingZoneIdToParkingZoneDepotData,
          beamServices.geo.distUTMInMeters,
          DefaultRideHailDepotParkingManager.SearchStartRadius,
          DefaultRideHailDepotParkingManager.SearchMaxRadius,
          boundingBox,
          beamServices.beamConfig.matsim.modules.global.randomSeed,
          beamServices.beamScenario.fuelTypePrices,
          beamServices.beamConfig.beam.agentsim.agents.rideHail,
          beamServices.skims
        )
      )
    }
  }

  def init(
    vehicleManagerId: Id[VehicleManager],
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[TAZ]],
    boundingBox: Envelope,
    beamServices: BeamServices
  ): RideHailDepotParkingManager[TAZ] = {
    DefaultRideHailDepotParkingManager[TAZ](
      vehicleManagerId,
      parkingZones,
      beamServices.beamScenario.tazTreeMap.tazQuadTree,
      beamServices.beamScenario.tazTreeMap.idToTAZMapping,
      identity[TAZ],
      boundingBox,
      beamServices
    )
  }

  def init(
    vehicleManagerId: Id[VehicleManager],
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[Link]],
    geoQuadTree: QuadTree[Link],
    idToGeoMapping: scala.collection.Map[Id[Link], Link],
    geoToTAZ: Link => TAZ,
    boundingBox: Envelope,
    beamServices: BeamServices
  ): RideHailDepotParkingManager[Link] = {
    DefaultRideHailDepotParkingManager[Link](
      vehicleManagerId,
      parkingZones,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      boundingBox,
      beamServices
    )
  }
}
