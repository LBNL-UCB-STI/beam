package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.infrastructure.ChargingNetwork.ChargingStation
import beam.agentsim.infrastructure.RideHailDepotFunctions.mnlMultiplierParametersFromConfig
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingZoneSearchResult
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode.CAR
import beam.router.skim.Skims
import beam.sim.config.BeamConfig
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.Random

class RideHailDepotFunctions(
  tazTreeMap: TAZTreeMap,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  fractionOfSameTypeZones: Double,
  minNumberOfSameTypeZones: Int,
  boundingBox: Envelope,
  seed: Int,
  fuelTypePrices: Map[FuelType, Double],
  rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail,
  skims: Skims,
  estimatedMinParkingDurationInSeconds: Double,
  depotsMap: Map[Id[ParkingZoneId], ChargingStation]
) extends InfrastructureFunctions(
      tazTreeMap,
      parkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      0.0,
      estimatedMinParkingDurationInSeconds,
      0.0,
      fractionOfSameTypeZones,
      minNumberOfSameTypeZones,
      boundingBox,
      seed
    ) {

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
    parkingAlternative: ParkingZoneSearch.ParkingAlternative,
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
    val queueTime =
      secondsToServiceQueueAndChargingVehicles(parkingAlternative.parkingZone, inquiry.destinationUtm.time)
    val chargingTime = beamVehicle
      .refuelingSessionDurationAndEnergyInJoulesForStall(
        Some(
          ParkingStall
            .fromParkingAlternative(parkingAlternative.geo.tazId, inquiry.activityType, parkingAlternative)
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
    zone: ParkingZone,
    inquiry: ParkingInquiry
  ): Boolean = {
    val beamVehicle = inquiry.beamVehicle.get
    val isFastCharger = zone.chargingPointType.exists(ChargingPointType.isFastCharger)
    val hasLowSOC = beamVehicle.getStateOfCharge < 0.8
    val hasStalls = zone.maxStalls > 0
    hasStalls && isFastCharger && hasLowSOC
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult]
  ): Option[ParkingZoneSearchResult] = {
    val output = parkingZoneSearchResult match {
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
          s"found ${parkingZonesSeen.length} parking zones over $iterations iterations"
        )
        // override the sampled stall coordinate with the TAZ centroid -
        // we want all agents who park in this TAZ to park in the same location.
        val updatedParkingStall = parkingStall.copy(
          locationUTM = getParkingZoneLocationUtm(parkingZone.parkingZoneId)
        )
        result.copy(parkingStall = updatedParkingStall)
      case _ =>
        // didn't find any stalls, so, as a last resort, create a very expensive stall
        val boxAroundRequest = new Envelope(
          inquiry.destinationUtm.loc.getX + 2000,
          inquiry.destinationUtm.loc.getX - 2000,
          inquiry.destinationUtm.loc.getY + 2000,
          inquiry.destinationUtm.loc.getY - 2000
        )
        val newStall = ParkingStall.lastResortStall(boxAroundRequest, new Random(seed))
        ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
    }
    Some(output)
  }

  /**
    * sample location of a parking stall with a TAZ area
    *
    * @param inquiry     ParkingInquiry
    * @param parkingZone ParkingZone
    * @param taz TAZ
    * @return
    */
  override protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone,
    taz: TAZ,
    inClosestZone: Boolean = true
  ): Coord = {
    taz.coord
  }

  /**
    * Estimates the amount of time a vehicle will spend waiting for its turn to charge. The estimate is an average wait time
    * calculated as the sum of all remaining time needed to for actively charging vehicles (if all plugs are in use, otherwise this is zero)
    * plus the time needed to charge all vehicles in the current queue, all divided by the number of plugs (of the same plug type) in this depot.
    *
    * @param parkingZone the zone for which an estimate is desired
    * @param tick Int
    * @return
    */
  def secondsToServiceQueueAndChargingVehicles(
    parkingZone: ParkingZone,
    tick: Int
  ): Int = {
    val chargingVehicles = depotsMap.get(parkingZone.parkingZoneId).map(_.howManyVehiclesAreCharging).getOrElse(0)
    val remainingChargeDurationFromPluggedInVehicles = if (chargingVehicles < parkingZone.maxStalls) {
      0
    } else {
      depotsMap.get(parkingZone.parkingZoneId).map(_.remainingChargeDurationFromPluggedInVehicles(tick)).sum
    }
    val chargeDurationFromQueue =
      depotsMap.get(parkingZone.parkingZoneId).map(_.remainingChargeDurationForVehiclesFromQueue).sum
    val numVehiclesOnWayToDepot =
      depotsMap.get(parkingZone.parkingZoneId).map(_.howManyVehiclesOnTheWayToStation).sum
    val chargingQueue =
      depotsMap.get(parkingZone.parkingZoneId).map(_.howManyVehiclesAreWaiting).getOrElse(0)
    val vehiclesOnWayAdjustmentFactor = chargingQueue match {
      case numInQueue if numInQueue == 0 =>
        1.0
      case numInQueue =>
        1.0 + numVehiclesOnWayToDepot.toDouble / numInQueue.toDouble
    }
    val adjustedQueueServiceTime = chargeDurationFromQueue.toDouble * vehiclesOnWayAdjustmentFactor
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
  def getParkingZoneLocationUtm(parkingZoneId: Id[ParkingZoneId]): Coord = {
    val parkingZone = parkingZones(parkingZoneId)
    parkingZone.link.fold {
      tazTreeMap.idToTAZMapping(parkingZone.tazId).coord
    } {
      _.getCoord
    }
  }
}

object RideHailDepotFunctions {

  def mnlMultiplierParametersFromConfig(
    rideHailConfig: BeamConfig.Beam.Agentsim.Agents.RideHail
  ): Map[ParkingMNL.Parameters, UtilityFunctionOperation] = {
    Map(
      ParkingMNL.Parameters.DrivingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.multinomialLogit.params.drivingTimeMultiplier
      ),
      ParkingMNL.Parameters.QueueingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.multinomialLogit.params.queueingTimeMultiplier
      ),
      ParkingMNL.Parameters.ChargingTimeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.multinomialLogit.params.chargingTimeMultiplier
      ),
      ParkingMNL.Parameters.InsufficientRangeCost -> UtilityFunctionOperation.Multiplier(
        rideHailConfig.charging.multinomialLogit.params.insufficientRangeMultiplier
      )
    )
  }
}
