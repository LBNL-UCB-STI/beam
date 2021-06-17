package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.{BeamVehicle, ChargingCapability, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

class ChargingFunctions[GEO: GeoLevel](
  vehicleManagerId: Id[VehicleManager],
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  seed: Int,
  mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
  chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
) extends ParkingFunctions[GEO](
      vehicleManagerId,
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
    * @param activityTypeLowerCased a String expressing activity Type in lower case
    * @return
    */
  def ifRideHailThenFastChargingOnly(zone: ParkingZone[GEO], activityTypeLowerCased: String): Boolean =
    activityTypeLowerCased match {
      case "charge" =>
        zone.chargingPointType match {
          case Some(chargingPointType) => ChargingPointType.isFastCharger(chargingPointType)
          case None                    => false // requiring fast chargers only
        }
      case _ => true // not a ride hail vehicle seeking charging
    }

  /**
    * Method that verifies if the vehicle has valid charging capability
    * @param zone ParkingZone
    * @param beamVehicleMaybe Option[BeamVehicle]
    * @return
    */
  def hasValidChargingCapability(zone: ParkingZone[GEO], beamVehicleMaybe: Option[BeamVehicle]): Boolean = {
    beamVehicleMaybe.forall(
      vehicle =>
        vehicle.beamVehicleType.chargingCapability match {

          // if the charging zone has no charging point then by default the vehicle has valid charging capability
          case Some(_) if zone.chargingPointType.isEmpty => true

          // if the vehicle is FC capable, it cannot charges in XFC charging points
          case Some(chargingCapability) if chargingCapability == ChargingCapability.DCFC =>
            ChargingPointType
              .getChargingPointInstalledPowerInKw(zone.chargingPointType.get) < chargingPointConfig.thresholdXFCinKW

          // if the vehicle is not capable of DCFC, it can only charges in level 1 and 2
          case Some(chargingCapability) if chargingCapability == ChargingCapability.AC =>
            ChargingPointType
              .getChargingPointInstalledPowerInKw(zone.chargingPointType.get) < chargingPointConfig.thresholdDCFCinKW

          // EITHER the vehicle is XFC capable and it can charges everywhere
          // OR the vehicle has no charging capability defined and we flag it as valid, to ensure backward compatibility
          case _ => true
      }
    )
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
    val hasChargingPoint: Boolean = zone.chargingPointType.isDefined

    val isEV: Boolean = inquiry.beamVehicle.forall(_.beamVehicleType.isEV)

    val rideHailFastChargingOnly: Boolean = ifRideHailThenFastChargingOnly(zone, inquiry.activityType)

    val validChargingCapability: Boolean = hasValidChargingCapability(zone, inquiry.beamVehicle)

    val preferredParkingTypes = getPreferredParkingTypes(inquiry)
    val canThisCarParkHere: Boolean = getCanThisCarParkHere(zone, inquiry, preferredParkingTypes)

    hasChargingPoint && isEV && rideHailFastChargingOnly && validChargingCapability && canThisCarParkHere
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
      value1 = rangeAnxietyFactor,
    )

    params
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult[GEO]
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult[GEO]]
  ): Option[ParkingZoneSearchResult[GEO]] = parkingZoneSearchResult

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
    geoArea: GEO
  ): Coord = super[ParkingFunctions].sampleParkingStallLocation(inquiry, parkingZone, geoArea)
}
