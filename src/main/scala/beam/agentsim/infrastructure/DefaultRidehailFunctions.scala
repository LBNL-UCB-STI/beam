package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.ridehail.ParkingZoneDepotData
import beam.agentsim.agents.ridehail.ParkingZoneDepotData.ChargingQueueEntry
import beam.agentsim.agents.ridehail.RideHailManager.VehicleId
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.DefaultRidehailFunctions.mnlMultiplierParametersFromConfig
import beam.agentsim.infrastructure.charging.ChargingPointType.CustomChargingPoint
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingZoneSearchResult
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode.CAR
import beam.router.skim.Skims
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.mutable

class DefaultRidehailFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  parkingZoneIdToParkingZoneDepotData: mutable.Map[Id[ParkingZoneId], ParkingZoneDepotData],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  seed: Int,
  fuelTypePrices: Map[FuelType, Double],
  rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail,
  skims: Skims
) extends InfrastructureFunctions[GEO](
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      parkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      seed
    ) {

  private val vehicleIdToEndRefuelTick: mutable.Map[VehicleId, Int] = mutable.Map.empty[VehicleId, Int]

  override protected val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation] =
    mnlMultiplierParametersFromConfig(rideHailConfig)

  /**
    * Generic method for updating MNL Parameters
    *
    * @param parkingAlternative ParkingAlternative
    * @param inquiry            ParkingInquiry
    * @return
    */
  override protected def setupMNLParameters(
    parkingAlternative: ParkingZoneSearch.ParkingAlternative[GEO],
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {
    val beamVehicle = inquiry.beamVehicle.get
    val travelTimeAndDistanceToDepot = skims.od_skimmer
      .getTimeDistanceAndCost(
        inquiry.destinationUtm.loc,
        parkingAlternative.coord,
        inquiry.destinationUtm.time,
        CAR,
        beamVehicle.beamVehicleType.id,
        beamVehicle.beamVehicleType,
        fuelTypePrices(beamVehicle.beamVehicleType.primaryFuelType)
      )
    val remainingRange = beamVehicle.getTotalRemainingRange
    val hasInsufficientRange =
      if (remainingRange < travelTimeAndDistanceToDepot.distance + rideHailConfig.rangeBufferForDispatchInMeters) 1.0
      else 0.0
    val queueTime = secondsToServiceQueueAndChargingVehicles(
      parkingAlternative.parkingZone,
      inquiry.destinationUtm.time
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

  /**
    * Generic method for adding new search filter to parking zones
    *
    * @param zone    ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  override protected def setupSearchFilterPredicates(
    zone: ParkingZone[GEO],
    inquiry: ParkingInquiry
  ): Boolean = {
    val beamVehicle = inquiry.beamVehicle.get
    zone.maxStalls > 0 && !hasHighSocAndZoneIsDCFast(beamVehicle, zone)
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult[GEO]
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult[GEO]]
  ): Option[ParkingZoneSearchResult[GEO]] = {
    parkingZoneSearchResult match {
      case Some(
            result @ ParkingZoneSearch.ParkingZoneSearchResult(
              parkingStall,
              parkingZone,
              parkingZonesSeen,
              _,
              iterations
            )
          ) =>
        logger.debug(
          s"found ${parkingZonesSeen.length} parking zones over ${iterations} iterations"
        )
        // override the sampled stall coordinate with the TAZ centroid -
        // we want all agents who park in this TAZ to park in the same location.
        val updatedParkingStall = parkingStall.copy(
          locationUTM = getParkingZoneLocationUtm(parkingZone.parkingZoneId)
        )
        Some(result.copy(parkingStall = updatedParkingStall))
      case _ => None
    }
  }

  /**
    * sample location of a parking stall with a GEO area
    *
    * @param inquiry     ParkingInquiry
    * @param parkingZone ParkingZone[GEO]
    * @param geoArea GEO
    * @return
    */
  override protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone[GEO],
    geoArea: GEO,
    inClosestZone: Boolean = true
  ): Coord = {
    import GeoLevel.ops._
    geoArea.centroidLocation
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
    val chargeDurationFromQueue = chargingQueue.map { case ChargingQueueEntry(beamVehicle, parkingStall, _) =>
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
    val adjustedQueueServiceTime =
      (chargeDurationFromQueue.toDouble + serviceTimeOfPhantomVehicles.toDouble) * vehiclesOnWayAdjustmentFactor
    val result = Math
      .round(
        (remainingChargeDurationFromPluggedInVehicles.toDouble + adjustedQueueServiceTime) / parkingZone.maxStalls
      )
      .toInt
    result
  }

  def hasHighSocAndZoneIsDCFast(beamVehicle: BeamVehicle, parkingZone: ParkingZone[GEO]): Boolean = {
    val soc = beamVehicle.primaryFuelLevelInJoules / beamVehicle.beamVehicleType.primaryFuelCapacityInJoule
    soc >= 0.8 && parkingZone.chargingPointType.exists(_.asInstanceOf[CustomChargingPoint].installedCapacity > 20.0)
  }

  /**
    * Gets the location in UTM for a parking zone.
    *
    * @param parkingZoneId ID of the parking zone
    * @return Parking zone location in UTM.
    */
  def getParkingZoneLocationUtm(parkingZoneId: Id[ParkingZoneId]): Coord = {
    val geoId = parkingZones(parkingZoneId).geoId
    val geo = idToGeoMapping(geoId)
    import GeoLevel.ops._
    geo.centroidLocation
  }
}

object DefaultRidehailFunctions {

  def mnlMultiplierParametersFromConfig(
    rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail
  ): Map[ParkingMNL.Parameters, UtilityFunctionOperation] = {
    Map(
      ParkingMNL.Parameters.DrivingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.drivingTimeMultiplier
      ),
      ParkingMNL.Parameters.QueueingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.queueingTimeMultiplier
      ),
      ParkingMNL.Parameters.ChargingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.chargingTimeMultiplier
      ),
      ParkingMNL.Parameters.InsufficientRangeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.vehicleChargingManager.defaultVehicleChargingManager.mulitnomialLogit.params.insufficientRangeMultiplier
      )
    )
  }
}
