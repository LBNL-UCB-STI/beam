package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.config.BeamConfig
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ParkingFunctions[GEO: GeoLevel](
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
  mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit
) extends InfrastructureFunctions[GEO](
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
      seed
    ) {

  override protected val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation] = Map(
    ParkingMNL.Parameters.RangeAnxietyCost -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.rangeAnxietyMultiplier
    ),
    ParkingMNL.Parameters.WalkingEgressCost -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.distanceMultiplier
    ),
    ParkingMNL.Parameters.ParkingTicketCost -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.parkingPriceMultiplier
    ),
    ParkingMNL.Parameters.HomeActivityPrefersResidentialParking -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.homeActivityPrefersResidentialParkingMultiplier
    ),
    ParkingMNL.Parameters.EnrouteDetourCost -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.enrouteDetourMultiplier
    )
  )

  /**
    * Generic method for updating MNL Parameters
    *
    * @param parkingAlternative ParkingAlternative
    * @param inquiry            ParkingInquiry
    * @return
    */
  override protected def setupMNLParameters(
    parkingAlternative: ParkingAlternative[GEO],
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {
    val distance: Double = distanceFunction(inquiry.destinationUtm.loc, parkingAlternative.coord)

    val distanceFactor: Double =
      (distance / ZonalParkingManager.AveragePersonWalkingSpeed / ZonalParkingManager.HourInSeconds) * inquiry.valueOfTime

    val parkingCostsPriceFactor: Double = parkingAlternative.costInDollars

    val goingHome: Boolean =
      inquiry.parkingActivityType == ParkingActivityType.Home && parkingAlternative.parkingType == ParkingType.Residential

    val homeActivityPrefersResidentialFactor: Double = if (goingHome) 1.0 else 0.0

    // end-of-day parking durations are set to zero, which will be mis-interpreted here
    val tempParkingDuration = inquiry.searchMode match {
      case ParkingSearchMode.EnRoute => enrouteDuration.toInt
      case _                         => inquiry.parkingDuration.toInt
    }
    val parkingDuration: Option[Int] = if (tempParkingDuration <= 0) None else Some(tempParkingDuration)

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

    val params: Map[ParkingMNL.Parameters, Double] = Map(
      ParkingMNL.Parameters.RangeAnxietyCost                      -> rangeAnxietyFactor,
      ParkingMNL.Parameters.WalkingEgressCost                     -> distanceFactor,
      ParkingMNL.Parameters.ParkingTicketCost                     -> parkingCostsPriceFactor,
      ParkingMNL.Parameters.HomeActivityPrefersResidentialParking -> homeActivityPrefersResidentialFactor,
      ParkingMNL.Parameters.EnrouteDetourCost                     -> 0.0
    )

    params
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
    if (zone.chargingPointType.isDefined)
      throw new RuntimeException("ParkingFunctions expect only stalls without charging points")
    val preferredParkingTypes = getPreferredParkingTypes(inquiry)
    val canCarParkHere: Boolean = canThisCarParkHere(zone, inquiry, preferredParkingTypes)
    canCarParkHere
  }

  /**
    * Generic method that specifies the behavior when MNL returns a ParkingZoneSearchResult
    * @param parkingZoneSearchResult ParkingZoneSearchResult[GEO]
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult[GEO]]
  ): Option[ParkingZoneSearchResult[GEO]] = {
    val output = parkingZoneSearchResult match {
      case Some(result) => result
      case _ =>
        inquiry.parkingActivityType match {
          case ParkingActivityType.Init | ParkingActivityType.Home =>
            val newStall = ParkingStall.defaultResidentialStall(inquiry.destinationUtm.loc, GeoLevel[GEO].defaultGeoId)
            ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
          case _ =>
            // didn't find any stalls, so, as a last resort, create a very expensive stall
            val boxAroundRequest = new Envelope(
              inquiry.destinationUtm.loc.getX + 2000,
              inquiry.destinationUtm.loc.getX - 2000,
              inquiry.destinationUtm.loc.getY + 2000,
              inquiry.destinationUtm.loc.getY - 2000
            )
            val newStall = ParkingStall.lastResortStall(
              boxAroundRequest,
              new Random(seed),
              tazId = TAZ.EmergencyTAZId,
              geoId = GeoLevel[GEO].emergencyGeoId
            )
            ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
        }
    }
    Some(output)
  }

  /**
    * sample location of a parking stall with a GEO area
    *
    * @param inquiry     ParkingInquiry
    * @param parkingZone ParkingZone[GEO]
    * @param geoArea GEO
    */
  override protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone[GEO],
    geoArea: GEO,
    inClosestZone: Boolean = true
  ): Coord = {
    if (parkingZone.link.isDefined)
      parkingZone.link.get.getCoord
    else if (parkingZone.reservedFor.managerType == VehicleManager.TypeEnum.Household)
      inquiry.destinationUtm.loc
    else
      GeoLevel[GEO].geoSampling(
        new Random(seed),
        inquiry.destinationUtm.loc,
        geoArea,
        parkingZone.availability,
        inClosestZone
      )
  }

  /**
    * Can This Car Park Here
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @param preferredParkingTypes Set[ParkingType]
    * @return
    */
  protected def canThisCarParkHere(
    zone: ParkingZone[GEO],
    inquiry: ParkingInquiry,
    preferredParkingTypes: Set[ParkingType]
  ): Boolean = {
    val originalZones = aggregatedZonesToAllZones(zone.parkingZoneId).map(parkingZones)

    val isValidVehicleManager = zone.reservedFor.managerType match {
      case VehicleManager.TypeEnum.Default   => !inquiry.beamVehicle.exists(_.isCAV)
      case VehicleManager.TypeEnum.NoManager => false
      case _                                 => originalZones.exists(_.reservedFor == inquiry.reservedFor)
    }

    val isValidParkingType: Boolean = preferredParkingTypes.contains(zone.parkingType)

    val hasAvailability = originalZones.exists { z =>
      z.stallsAvailable > 0 && inquiry.beamVehicle.forall(vehicle =>
        z.timeRestrictions
          .get(vehicle.beamVehicleType.vehicleCategory)
          .forall(_.contains(inquiry.destinationUtm.time % (24 * 3600)))
      )
    }

    hasAvailability & isValidParkingType & isValidVehicleManager
  }

  /**
    * Preferred Parking Types
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getPreferredParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    // a lookup for valid parking types based on this inquiry
    if (inquiry.searchMode == ParkingSearchMode.EnRoute) Set(ParkingType.Public)
    else {
      inquiry.parkingActivityType match {
        case ParkingActivityType.Home   => Set(ParkingType.Residential, ParkingType.Public)
        case ParkingActivityType.Init   => Set(ParkingType.Residential, ParkingType.Public)
        case ParkingActivityType.Work   => Set(ParkingType.Workplace, ParkingType.Public)
        case ParkingActivityType.Charge => Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
        case _                          => Set(ParkingType.Public)
      }
    }
  }
}
