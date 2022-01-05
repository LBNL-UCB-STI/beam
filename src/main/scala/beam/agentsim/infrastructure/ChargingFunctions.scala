package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.{Skims, SkimsUtils}
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ChargingFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  searchMaxDistanceRelativeToEllipseFoci: Double,
  enrouteDuration: Double,
  boundingBox: Envelope,
  seed: Int,
  mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
  skims: Option[Skims],
  fuelPrice: Map[FuelType, Double]
) extends ParkingFunctions[GEO](
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      parkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      searchMaxDistanceRelativeToEllipseFoci,
      enrouteDuration,
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
    * function that verifies if Enroute Then Fast Charging Only
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  def ifEnrouteChargingTheFastChargingOnly(zone: ParkingZone[GEO], inquiry: ParkingInquiry): Boolean = {
    inquiry.searchMode match {
      case ParkingSearchMode.EnRoute =>
        ChargingPointType.isFastCharger(zone.chargingPointType.get)
      case _ =>
        true // if it is not Enroute charging then it does not matter
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
    val enrouteFastChargingOnly: Boolean = ifEnrouteChargingTheFastChargingOnly(zone, inquiry)
    val validChargingCapability: Boolean = hasValidChargingCapability(zone, inquiry.beamVehicle)
    val preferredParkingTypes = getPreferredParkingTypes(inquiry)
    val canCarParkHere: Boolean = canThisCarParkHere(zone, inquiry, preferredParkingTypes)
    isEV && rideHailFastChargingOnly && validChargingCapability && canCarParkHere && enrouteFastChargingOnly
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
    val parkingParameters = inquiry.searchMode match {
      case ParkingSearchMode.EnRoute =>
        val beamVehicle = inquiry.beamVehicle.get
        val origin = inquiry.originUtm.getOrElse(
          throw new RuntimeException(s"Enroute requires an origin location in parking inquiry $inquiry")
        )
        val travelTime1 = getTravelTime(origin.loc, parkingAlternative.coord, origin.time, beamVehicle.beamVehicleType)
        val travelTime2 = getTravelTime(
          parkingAlternative.coord,
          inquiry.destinationUtm.loc,
          origin.time + travelTime1,
          beamVehicle.beamVehicleType
        )
        val enrouteFactor: Double = (travelTime1 + travelTime2) * inquiry.valueOfTime
        Map(ParkingMNL.Parameters.EnrouteDetourCost -> enrouteFactor)
      case _ => Map()
    }
    super[ParkingFunctions].setupMNLParameters(parkingAlternative, inquiry) ++ parkingParameters
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult[GEO]
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult[GEO]]
  ): Option[ParkingZoneSearchResult[GEO]] = parkingZoneSearchResult match {
    case None if inquiry.searchMode == ParkingSearchMode.EnRoute =>
      // did not find a stall with a fast charging point, return a dummy stall
      Some(
        ParkingZoneSearch.ParkingZoneSearchResult(
          ParkingStall.lastResortStall(
            new Envelope(
              inquiry.originUtm.get.loc.getX + 2000,
              inquiry.originUtm.get.loc.getX - 2000,
              inquiry.originUtm.get.loc.getY + 2000,
              inquiry.originUtm.get.loc.getY - 2000
            ),
            new Random(seed),
            tazId = TAZ.EmergencyTAZId,
            geoId = GeoLevel[GEO].emergencyGeoId
          ),
          DefaultParkingZone
        )
      )
    case resultMaybe => resultMaybe
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
    geoArea: GEO
  ): Coord = super[ParkingFunctions].sampleParkingStallLocation(inquiry, parkingZone, geoArea)

  /**
    * getTravelTime
    * @param origin Coord
    * @param dest Coord
    * @param depTime Integer
    * @param beamVehicleType BeamVehicleType
    * @return
    */
  private def getTravelTime(origin: Coord, dest: Coord, depTime: Int, beamVehicleType: BeamVehicleType): Int = {
    skims map { skim =>
      skim.od_skimmer
        .getTimeDistanceAndCost(
          origin,
          dest,
          depTime,
          BeamMode.CAR,
          beamVehicleType.id,
          beamVehicleType,
          fuelPrice.getOrElse(beamVehicleType.primaryFuelType, 0.0)
        )
        .time
    } getOrElse SkimsUtils.distanceAndTime(BeamMode.CAR, origin, dest)._2
  }
}
