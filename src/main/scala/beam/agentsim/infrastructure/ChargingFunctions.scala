package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

class ChargingFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  seed: Int,
  mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit
) extends ParkingFunctions[GEO](
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      parkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      seed,
      mnlParkingConfig
    ) {

  /**
    * function that verifies if RideHail Then Fast Charging Only
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifRideHailCurrentlyOnShiftThenFastChargingOnly(zone: ParkingZone[GEO], inquiry: ParkingInquiry): Boolean = {
    inquiry.reservedFor match {
      case VehicleManager.TypeEnum.RideHail if inquiry.parkingDuration <= 3600 =>
        ChargingPointType.isFastCharger(zone.chargingPointType.get)
      case _ =>
        true // not a ride hail vehicle seeking charging or parking for two then it is fine to park at slow charger
    }
  }

  /**
    * Method that verifies if the vehicle has valid charging capability
    * @param zone ParkingZone
    * @param beamVehicleMaybe Option[BeamVehicle]
    * @return
    */
  def hasValidChargingCapability(zone: ParkingZone[GEO], beamVehicleMaybe: Option[BeamVehicle]): Boolean = {
    beamVehicleMaybe.forall(
      _.beamVehicleType.chargingCapability.forall(getPower(_) >= getPower(zone.chargingPointType.get))
    )
  }

  private def getPower(implicit chargingCapability: ChargingPointType): Double = {
    ChargingPointType.getChargingPointInstalledPowerInKw(chargingCapability)
  }

  /**
    * get Additional Search Filter Predicates
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  override protected def setupSearchFilterPredicates(
    zone: ParkingZone[GEO],
    inquiry: ParkingInquiry
  ): Boolean = {
    if (zone.chargingPointType.isEmpty)
      throw new RuntimeException("ChargingFunctions expect only stalls with charging points")

    val isEV: Boolean = inquiry.beamVehicle.forall(v => v.isBEV || v.isPHEV)

    val rideHailFastChargingOnly: Boolean = ifRideHailCurrentlyOnShiftThenFastChargingOnly(zone, inquiry)

    val validChargingCapability: Boolean = hasValidChargingCapability(zone, inquiry.beamVehicle)

    val preferredParkingTypes = getPreferredParkingTypes(inquiry)
    val hasAvailability: Boolean = parkingZones(zone.parkingZoneId).stallsAvailable > 0

    val validParkingType: Boolean = preferredParkingTypes.contains(zone.parkingType)

    val isValidTime = inquiry.beamVehicle.forall(vehicle =>
      zone.timeRestrictions
        .get(vehicle.beamVehicleType.vehicleCategory)
        .forall(_.contains(inquiry.destinationUtm.time % (24 * 3600)))
    )

    val isValidVehicleManager = inquiry.beamVehicle.forall { vehicle =>
      zone.reservedFor.managerType == VehicleManager.TypeEnum.Default || zone.reservedFor.managerId == vehicle.vehicleManagerId.get
    }

    inquiry.beamVehicle.foreach { v =>
      logger.info(
        s"SEARCH: ${zone.parkingZoneId},${zone.geoId},${zone.parkingType},${zone.chargingPointType.getOrElse("NoCharger")}," +
        s"${zone.pricingModel.get},${zone.reservedFor},${zone.stallsAvailable},${zone.maxStalls},${v.id},${inquiry.parkingDuration}," +
        s"${inquiry.activityType},${inquiry.valueOfTime},${inquiry.requestId},${isEV},${rideHailFastChargingOnly},${validChargingCapability}," +
        s"${hasAvailability},${validParkingType},${isValidTime},${isValidVehicleManager}"
      )
    }

    val canCarParkHere: Boolean = hasAvailability & validParkingType & isValidTime & isValidVehicleManager
    isEV && rideHailFastChargingOnly && validChargingCapability && canCarParkHere
  }

  /**
    * Update MNL Parameters
    * @param parkingAlternative ParkingAlternative
    * @param inquiry ParkingInquiry
    *  @return
    */
  override protected def setupMNLParameters(
    parkingAlternative: ParkingAlternative[GEO],
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {

    val parkingParameters = super[ParkingFunctions].setupMNLParameters(parkingAlternative, inquiry)

    // end-of-day parking durations are set to zero, which will be mis-interpreted here
    val parkingDuration: Option[Int] =
      if (inquiry.parkingDuration <= 0) None
      else Some(inquiry.parkingDuration.toInt)

    val addedEnergy: Double =
      inquiry.beamVehicle match {
        case Some(beamVehicle) =>
          parkingAlternative.parkingZone.chargingPointType match {
            case Some(chargingPoint) =>
              val (_, addedEnergy) = ChargingPointType.calculateChargingSessionLengthAndEnergyInJoule(
                chargingPoint,
                beamVehicle.primaryFuelLevelInJoules,
                beamVehicle.beamVehicleType.primaryFuelCapacityInJoule,
                1e6,
                1e6,
                parkingDuration
              )
              addedEnergy
            case None => 0.0 // no charger here
          }
        case None => 0.0 // no beamVehicle, assume agent has range
      }

    val rangeAnxietyFactor: Double =
      inquiry.remainingTripData
        .map {
          _.rangeAnxiety(withAddedFuelInJoules = addedEnergy)
        }
        .getOrElse(0.0) // default no anxiety if no remaining trip data provided

    val params = parkingParameters ++ new Map.Map1(
      key1 = ParkingMNL.Parameters.RangeAnxietyCost,
      value1 = rangeAnxietyFactor
    )

    inquiry.beamVehicle.foreach { v =>
      logger.info(
        s"PARAM: ${parkingAlternative.parkingZone.parkingZoneId},${parkingAlternative.parkingZone.geoId},${parkingAlternative.parkingZone.parkingType}," +
        s"${parkingAlternative.parkingZone.chargingPointType.getOrElse("NoCharger")},${parkingAlternative.parkingZone.pricingModel.get}," +
        s"${parkingAlternative.parkingZone.reservedFor},${parkingAlternative.parkingZone.stallsAvailable},${parkingAlternative.parkingZone.maxStalls},${v.id},${inquiry.parkingDuration}," +
        s"${inquiry.activityType},${inquiry.valueOfTime},${inquiry.requestId},${parkingAlternative.costInDollars},${params(ParkingMNL.Parameters.RangeAnxietyCost)}," +
        s"${params(ParkingMNL.Parameters.WalkingEgressCost)},${params(ParkingMNL.Parameters.ParkingTicketCost)},${params(ParkingMNL.Parameters.HomeActivityPrefersResidentialParking)}"
      )
    }

    params
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
      case Some(result) =>
        result.parkingZonesSampled.foreach { case (parkingZoneId, chargingPointTypeMaybe, parkingType, costInDollars) =>
          logger.info(
            s"SAMPLED: ${inquiry.requestId},${parkingZoneId},${chargingPointTypeMaybe
              .getOrElse("NoCharger")},${parkingType},${costInDollars}"
          )
        }
        logger.info(
          s"CHOSEN: ${inquiry.requestId},${result.parkingStall.parkingZoneId},${result.parkingStall.chargingPointType
            .getOrElse("NoCharger")},${result.parkingStall.parkingType},${result.parkingStall.costInDollars}"
        )
      case _ =>
    }
    parkingZoneSearchResult
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
    inClosestZone: Boolean = false
  ): Coord = super[ParkingFunctions].sampleParkingStallLocation(inquiry, parkingZone, geoArea, inClosestZone)
}
