package beam.agentsim.agents.ridehail

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.StartRefuelSessionTrigger
import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.{RefuelSource, VehicleId}
import beam.agentsim.agents.ridehail.charging.StallAssignmentStrategy
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams
}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.ScheduleTrigger
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode.CAR
import beam.router.skim.Skims
import beam.sim.{BeamServices, Geofence}
import beam.utils.logging.LogActorState
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Random, Success, Try}

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
  *
  * @param parkingFilePath
  * @param valueOfTime
  * @param tazTreeMap
  * @param random
  * @param boundingBox
  * @param distFunction
  * @param parkingStallCountScalingFactor
  * @param beamServices
  */
class DefaultRideHailDepotParkingManager[GEO: GeoLevel](
  parkingFilePath: String,
  valueOfTime: Double,
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  random: Random,
  boundingBox: Envelope,
  distFunction: (Location, Location) => Double,
  parkingStallCountScalingFactor: Double = 1.0,
  beamServices: BeamServices,
  skims: Skims,
  outputDirectory: OutputDirectoryHierarchy
) extends RideHailDepotParkingManager[GEO] {

  // load parking from a parking file, or generate it using the geo beam input
  val (
    rideHailParkingZones: Array[ParkingZone[GEO]],
    rideHailParkingSearchTree: ParkingZoneSearch.ZoneSearchTree[GEO]
  ) = if (parkingFilePath.isEmpty) {
    logger.info(s"no parking file found. generating ubiquitous ride hail parking")
    ParkingZoneFileUtils
      .generateDefaultParkingFromGeoObjects(geoQuadTree.values().asScala, random, Seq(ParkingType.Workplace))
  } else {
    Try {
      ParkingZoneFileUtils.fromFile[GEO](parkingFilePath, random, parkingStallCountScalingFactor)
    } match {
      case Success((stalls, tree)) =>
        logger.info(s"generating ride hail parking from file $parkingFilePath")
        (stalls, tree)
      case Failure(e) =>
        logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
        logger.info(s"generating ubiquitous ride hail parking")
        ParkingZoneFileUtils
          .generateDefaultParkingFromGeoObjects(geoQuadTree.values().asScala, random, Seq(ParkingType.Workplace))
    }
  }

  private val chargingPlugTypesSortedByPower = rideHailParkingZones
    .flatMap(_.chargingPointType)
    .distinct
    .sortBy(-ChargingPointType.getChargingPointInstalledPowerInKw(_))

  // track the usage of the RHM agency parking
  var totalStallsInUse: Long = 0
  var totalStallsAvailable: Long = 0

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      DefaultRideHailDepotParkingManager.SearchStartRadius,
      DefaultRideHailDepotParkingManager.SearchMaxRadius,
      boundingBox,
      distFunction
    )

  ParkingZoneFileUtils.toCsv(
    rideHailParkingZones,
    outputDirectory.getOutputFilename(DefaultRideHailDepotParkingManager.outputRidehailParkingFileName)
  )

  val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation] = Map(
    ParkingMNL.Parameters.DrivingTimeCost -> UtilityFunctionOperation.Multiplier(
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.drivingTimeMultiplier
    ),
    ParkingMNL.Parameters.QueueingTimeCost -> UtilityFunctionOperation.Multiplier(
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.queueingTimeMultiplier
    ),
    ParkingMNL.Parameters.ChargingTimeCost -> UtilityFunctionOperation.Multiplier(
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.chargingTimeMultiplier
    ),
    ParkingMNL.Parameters.InsufficientRangeCost -> UtilityFunctionOperation.Multiplier(
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.insufficientRangeMultiplier
    )
  )

  def fastestChargingPlugType: ChargingPointType = chargingPlugTypesSortedByPower.head

  type ParkingZoneId = Int

  /*
   *  Maps from VehicleId -> XX
   */
  private val chargingVehicleToParkingStallMap: mutable.Map[VehicleId, ParkingStall] =
    mutable.Map.empty[VehicleId, ParkingStall]
  private val vehiclesOnWayToDepot: mutable.Map[VehicleId, ParkingStall] = mutable.Map.empty[VehicleId, ParkingStall]
  private val vehicleIdToEndRefuelTick: mutable.Map[VehicleId, Int] = mutable.Map.empty[VehicleId, Int]
  private val vehiclesInQueueToParkingZoneId: mutable.Map[VehicleId, ParkingZoneId] =
    mutable.Map.empty[VehicleId, ParkingZoneId]
  private val vehicleIdToLastObservedTickAndAction: mutable.Map[VehicleId, mutable.ListBuffer[(Int, String)]] =
    mutable.Map.empty[VehicleId, mutable.ListBuffer[(Int, String)]]
  private val vehicleIdToGeofence: mutable.Map[VehicleId, Geofence] = mutable.Map.empty[VehicleId, Geofence]
  /*
   * All internal data to track Depots, ParkingZones, and charging queues are kept in ParkingZoneDepotData which is
   * accessible via a Map on the ParkingZoneId
   */
  private val parkingZoneIdToParkingZoneDepotData: mutable.Map[ParkingZoneId, ParkingZoneDepotData] =
    mutable.Map.empty[ParkingZoneId, ParkingZoneDepotData]
  rideHailParkingZones.foreach(
    parkingZone => parkingZoneIdToParkingZoneDepotData.put(parkingZone.parkingZoneId, ParkingZoneDepotData.empty)
  )

  /*
   * Track "Depots" as a mapping from TAZ Id to ParkingZones to facilitate processing all ParkingZones in a depot
   */
  val tazIdToParkingZones: mutable.Map[Id[GEO], Array[ParkingZone[GEO]]] =
    mutable.Map.empty[Id[GEO], Array[ParkingZone[GEO]]]
  rideHailParkingZones.groupBy(_.geoId).foreach(tup => tazIdToParkingZones += tup)

  // FIXME Unused value
  private val stallAssignmentStrategy: Try[StallAssignmentStrategy] =
    beamServices.beamCustomizationAPI.getStallAssignmentStrategyFactory.create(
      this,
      beamServices.beamConfig.beam.agentsim.agents.rideHail.charging.vehicleChargingManager.depotManager.stallAssignmentStrategy.name
    )

  def registerGeofences(vehicleIdToGeofenceMap: mutable.Map[VehicleId, Option[Geofence]]) = {
    vehicleIdToGeofenceMap.foreach {
      case (vehicleId, Some(geofence)) =>
        vehicleIdToGeofence.put(vehicleId, geofence)
      case (_, _) =>
    }
  }

  /**
    * searches for a nearby [[ParkingZone]] depot for CAV Ride Hail Agents and returns a [[ParkingStall]] in that zone.
    *
    * all parking stalls are expected to be associated with a TAZ stored in the beamScenario.tazTreeMap.
    * the position of the stall will be at the centroid of the TAZ.
    *
    * @param locationUtm the position of this agent
    * @param beamVehicle the [[BeamVehicle]] associated with the driver
    * @param currentTick
    * @param findDepotAttributes extensible data structure allowing customization of data to be passed to DepotManager
    * @return the ParkingStall, or, nothing if no parking is available
    */
  def findDepot(
    locationUtm: Location,
    beamVehicle: BeamVehicle,
    currentTick: Int,
    findDepotAttributes: Option[FindDepotAttributes]
  ): Option[ParkingStall] = {

    val parkingZoneSearchParams: ParkingZoneSearchParams[GEO] =
      ParkingZoneSearchParams(
        locationUtm,
        beamVehicle.refuelingSessionDurationAndEnergyInJoules(None, None, None)._1,
        mnlMultiplierParameters,
        rideHailParkingSearchTree,
        rideHailParkingZones,
        geoQuadTree,
        random
      )

    val parkingZoneFilterFunction: ParkingZone[GEO] => Boolean = (zone: ParkingZone[GEO]) => {
      zone.maxStalls > 0 && !hasHighSocAndZoneIsDCFast(beamVehicle, zone)
    }

    // generates a coordinate for an embodied ParkingStall from a ParkingZone,
    // treating the TAZ centroid as a "depot" location
    val parkingZoneLocSamplingFunction: ParkingZone[GEO] => Location =
      (zone: ParkingZone[GEO]) => {
        import GeoLevel.ops._
        idToGeoMapping.get(zone.geoId) match {
          case None =>
            logger.error(
              s"somehow have a ParkingZone with geoId ${zone.geoId} which is not found in the idToGeoMapping"
            )
            new Location()
          case Some(geoLevel) =>
            geoLevel.centroidLocation
        }
      }

    // adds multinomial logit parameters to a ParkingAlternative
    val parkingZoneMNLParamsFunction: ParkingAlternative[GEO] => Map[ParkingMNL.Parameters, Double] =
      (parkingAlternative: ParkingAlternative[GEO]) => {
        val travelTimeAndDistanceToDepot = skims.od_skimmer
          .getTimeDistanceAndCost(
            locationUtm,
            parkingAlternative.coord,
            currentTick,
            CAR,
            beamVehicle.beamVehicleType.id,
            beamVehicle.beamVehicleType,
            beamServices.beamScenario.fuelTypePrices(beamVehicle.beamVehicleType.primaryFuelType),
            beamServices.beamScenario
          )
        val remainingRange = beamVehicle.getTotalRemainingRange
        val hasInsufficientRange =
          (remainingRange < travelTimeAndDistanceToDepot.distance + beamServices.beamConfig.beam.agentsim.agents.rideHail.rangeBufferForDispatchInMeters) match {
            case true =>
              1.0
            case false =>
              0.0
          }
        val queueTime = secondsToServiceQueueAndChargingVehicles(
          parkingAlternative.parkingZone,
          currentTick
        )
        val chargingTime = beamVehicle
          .refuelingSessionDurationAndEnergyInJoulesForStall(
            Some(
              ParkingStall
                .fromParkingAlternative(geoToTAZ(parkingAlternative.geo).tazId, parkingAlternative)
            ),
            None,
            None,
            None
          )
          ._1
        Map(
          ParkingMNL.Parameters.DrivingTimeCost       -> travelTimeAndDistanceToDepot.time,
          ParkingMNL.Parameters.QueueingTimeCost      -> queueTime,
          ParkingMNL.Parameters.ChargingTimeCost      -> chargingTime,
          ParkingMNL.Parameters.InsufficientRangeCost -> hasInsufficientRange
        )
      }

    for {
      ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, parkingZone, parkingZonesSeen, _, iterations) <- ParkingZoneSearch
        .incrementalParkingZoneSearch(
          parkingZoneSearchConfiguration,
          parkingZoneSearchParams,
          parkingZoneFilterFunction,
          parkingZoneLocSamplingFunction,
          parkingZoneMNLParamsFunction,
          geoToTAZ
        )
    } yield {

      logger.debug(s"found ${parkingZonesSeen.length} parking zones over $iterations iterations")

      // override the sampled stall coordinate with the TAZ centroid -
      // we want all agents who park in this TAZ to park in the same location.
      parkingStall.copy(
        locationUTM = getParkingZoneLocationUtm(parkingZone.parkingZoneId)
      )
    }
  }

  def hasHighSocAndZoneIsDCFast(beamVehicle: BeamVehicle, parkingZone: ParkingZone[GEO]): Boolean = {
    val soc = beamVehicle.primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelCapacityInJoule
    soc >= 0.8 && parkingZone.chargingPointType.exists(_.asInstanceOf[CustomChargingPoint].installedCapacity > 20.0)
  }

  /**
    * Estimates the amount of time a vehicle will spend waiting for its turn to charge. The estimate is an average wait time
    * calculated as the sum of all remaining time needed to for actively charging vehicles (if all plugs are in use, otherwise this is zero)
    * plus the time needed to charge all vehicles in the current queue, all divided by the number of plugs (of the same plug type) in this depot.
    *
    * @param parkingZone the zone for which an estimate is desired
    * @param tick
    * @return
    */
  def secondsToServiceQueueAndChargingVehicles(
    parkingZone: ParkingZone[GEO],
    tick: Int
  ): Int = {
    val parkingZoneDepotData = parkingZoneIdToParkingZoneDepotData(parkingZone.parkingZoneId)
    val chargingVehicles = parkingZoneDepotData.chargingVehicles
    val remainingChargeDurationFromPluggedInVehicles = if (chargingVehicles.size < parkingZone.maxStalls) {
      0
    } else {
      chargingVehicles.map(vehicleId => vehicleIdToEndRefuelTick.getOrElse(vehicleId, tick) - tick).toVector.sum
    }
    val serviceTimeOfPhantomVehicles = parkingZoneDepotData.serviceTimeOfQueuedPhantomVehicles
    val chargingQueue = parkingZoneDepotData.chargingQueue
    val chargeDurationFromQueue = chargingQueue.map {
      case ChargingQueueEntry(beamVehicle, parkingStall, _) =>
        beamVehicle.refuelingSessionDurationAndEnergyInJoulesForStall(Some(parkingStall), None, None, None)._1
    }.sum
    val numVehiclesOnWayToDepot = parkingZoneDepotData.vehiclesOnWayToDepot.size
    val numPhantomVehiclesInQueue = parkingZoneDepotData.numPhantomVehiclesQueued
    val vehiclesOnWayAdjustmentFactor = (numPhantomVehiclesInQueue + chargingQueue.size) match {
      case numInQueue if numInQueue == 0 =>
        1.0
      case numInQueue =>
        (1.0 + numVehiclesOnWayToDepot.toDouble / numInQueue.toDouble)
    }
    val adjustedQueueServiceTime = (chargeDurationFromQueue.toDouble + serviceTimeOfPhantomVehicles.toDouble) * vehiclesOnWayAdjustmentFactor
    val result = Math
      .round(
        (remainingChargeDurationFromPluggedInVehicles.toDouble + adjustedQueueServiceTime) / parkingZone.maxStalls
      )
      .toInt
    result
  }

  /**
    * Gets the location in UTM for a parking zone.
    *
    * @param parkingZoneId ID of the parking zone
    * @return Parking zone location in UTM.
    */
  def getParkingZoneLocationUtm(parkingZoneId: Int): Coord = {
    val geoId = rideHailParkingZones(parkingZoneId).geoId
    val geo = idToGeoMapping(geoId)
    import GeoLevel.ops._
    geo.centroidLocation
  }

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
    vehicleQueuePriority: Double,
    source: RefuelSource
  ): (Vector[ScheduleTrigger], Option[Int]) = {

    findAndClaimStallAtDepot(originalParkingStallFoundDuringAssignment) match {
      case Some(claimedParkingStall: ParkingStall) => {
        beamVehicle.useParkingStall(claimedParkingStall)
        if (addVehicleToChargingInDepotUsing(claimedParkingStall, beamVehicle, tick, source)) {
          (Vector(ScheduleTrigger(StartRefuelSessionTrigger(tick), beamVehicle.getDriver.get)), None)
        } else {
          (Vector(), None)
        }
      }
      case None =>
        addVehicleAndStallToRefuelingQueueFor(
          beamVehicle,
          originalParkingStallFoundDuringAssignment,
          vehicleQueuePriority,
          source
        )
        (Vector(), Some(originalParkingStallFoundDuringAssignment.parkingZoneId))
    }
  }

  /**
    * Given a parkingZoneId, dequeue the next vehicle that is waiting to charge.
    *
    * @param parkingZoneId
    * @return optional tuple with [[BeamVehicle]] and [[ParkingStall]]
    */
  def dequeueNextVehicleForRefuelingFrom(
    parkingZoneId: Int,
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
    * Looks up the ParkingZone associated with the parkingStall argument and claims a stall from that Zone if there
    * are any available, returning the stall as an output. Otherwise, if no stalls are available returns None.
    *
    * @param parkingStall the parking stall to claim
    * @return an optional parking stall that was assigned or None on failure
    */
  def findAndClaimStallAtDepot(
    parkingStall: ParkingStall
  ): Option[ParkingStall] = {
    if (parkingStall.parkingZoneId < 0 || rideHailParkingZones.length <= parkingStall.parkingZoneId) None
    else {
      val parkingZone: ParkingZone[GEO] = rideHailParkingZones(parkingStall.parkingZoneId)
      if (parkingZone.stallsAvailable == 0) {
        None
      } else {
        val success = ParkingZone.claimStall(parkingZone)
        if (!success) {
          None
        } else {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
          Some {
            parkingStall
          }
        }
      }
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
      putNewTickAndObservation(beamVehicle.id, (tick, s"Charging(${source})"))
      vehicleIdToEndRefuelTick.put(beamVehicle.id, tick + chargingSessionDuration)
      true
    }
  }

  /**
    * Store the last tick and action observed by this vehicle. For debugging purposes.
    *
    * @param vehicleId
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
      releaseStall(stall)
    }
    stallOpt
  }

  /**
    * releases a single stall in use at this Depot
    *
    * @param parkingStall stall we want to release
    * @return Boolean if zone is defined and remove was success
    */
  private def releaseStall(parkingStall: ParkingStall): Boolean = {
    if (parkingStall.parkingZoneId < 0 || rideHailParkingZones.length <= parkingStall.parkingZoneId) {
      false
    } else {
      val parkingZone: ParkingZone[GEO] = rideHailParkingZones(parkingStall.parkingZoneId)
      val success = ParkingZone.releaseStall(parkingZone)
      if (success) {
        totalStallsInUse -= 1
        totalStallsAvailable += 1
      }
      success
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
    if (chargingQueue.find(_.beamVehicle.id == vehicle.id).isDefined) {
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
    newVehiclesHeadedToDepot.foreach {
      case (vehicleId, parkingStall) =>
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
    vehiclesOnWayToDepot.contains(vehicleId) || chargingVehicleToParkingStallMap.contains(vehicleId) || vehiclesInQueueToParkingZoneId
      .contains(vehicleId)

  /**
    * Get all vehicles that are on the way to the refueling depot specified by the [[ParkingZone]] id.
    * @param parkingZoneId
    * @return a [[Vector]] of [[VechicleId]]s
    */
  def getVehiclesOnWayToRefuelingDepot(parkingZoneId: Int): Vector[VehicleId] =
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

  /**
    * Gives back the ParkingZones managed by the RidehailDepotParkingManager
    *
    * @return
    */
  override def getParkingZones(): Array[ParkingZone[GEO]] = rideHailParkingZones
}

object DefaultRideHailDepotParkingManager {

  // a ride hail agent is searching for a charging depot and is not in service of an activity.
  // for this reason, a higher max radius is reasonable.
  val SearchStartRadius: Double = 40000.0 // meters
  val SearchMaxRadius: Int = 80465 // 50 miles, in meters
  val outputRidehailParkingFileName = "ridehailParking.csv"

  def apply(
    parkingFilePath: String,
    valueOfTime: Double,
    tazTreeMap: TAZTreeMap,
    random: Random,
    boundingBox: Envelope,
    distFunction: (Location, Location) => Double,
    parkingStallCountScalingFactor: Double,
    beamServices: BeamServices,
    skims: Skims,
    outputDirectory: OutputDirectoryHierarchy
  ): RideHailDepotParkingManager[TAZ] = {
    new DefaultRideHailDepotParkingManager(
      parkingFilePath = parkingFilePath,
      valueOfTime = valueOfTime,
      geoQuadTree = tazTreeMap.tazQuadTree,
      idToGeoMapping = tazTreeMap.idToTAZMapping,
      geoToTAZ = identity[TAZ],
      random = random,
      boundingBox = boundingBox,
      distFunction = distFunction,
      parkingStallCountScalingFactor = parkingStallCountScalingFactor,
      beamServices = beamServices: BeamServices,
      skims = skims: Skims,
      outputDirectory = outputDirectory: OutputDirectoryHierarchy
    )
  }

  def apply(
    parkingFilePath: String,
    valueOfTime: Double,
    linkQuadTree: QuadTree[Link],
    linkIdMapping: Map[Id[Link], Link],
    linkToTAZMapping: Map[Link, TAZ],
    random: Random,
    boundingBox: Envelope,
    distFunction: (Location, Location) => Double,
    parkingStallCountScalingFactor: Double,
    beamServices: BeamServices,
    skims: Skims,
    outputDirectory: OutputDirectoryHierarchy
  ): RideHailDepotParkingManager[Link] = {
    new DefaultRideHailDepotParkingManager(
      parkingFilePath = parkingFilePath,
      valueOfTime = valueOfTime,
      geoQuadTree = linkQuadTree,
      idToGeoMapping = linkIdMapping,
      geoToTAZ = linkToTAZMapping,
      random = random,
      boundingBox = boundingBox,
      distFunction = distFunction,
      parkingStallCountScalingFactor = parkingStallCountScalingFactor,
      beamServices = beamServices,
      skims = skims,
      outputDirectory = outputDirectory
    )
  }
}
