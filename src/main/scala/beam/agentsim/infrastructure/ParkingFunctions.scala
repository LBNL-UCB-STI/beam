package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Parking
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ParkingFunctions(
  tazTreeMap: TAZTreeMap,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  searchMaxDistanceRelativeToEllipseFoci: Double,
  estimatedMinParkingDurationInSeconds: Double,
  estimatedMeanEnRouteChargingDurationInSeconds: Double,
  fractionOfSameTypeZones: Double,
  minNumberOfSameTypeZones: Int,
  boundingBox: Envelope,
  seed: Int,
  mnlParkingConfig: Parking.MultinomialLogit
) extends InfrastructureFunctions(
      tazTreeMap,
      parkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      searchMaxDistanceRelativeToEllipseFoci,
      estimatedMinParkingDurationInSeconds,
      estimatedMeanEnRouteChargingDurationInSeconds,
      fractionOfSameTypeZones,
      minNumberOfSameTypeZones,
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
    parkingAlternative: ParkingAlternative,
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {
    val distance: Double = distanceFunction(inquiry.destinationUtm.loc, parkingAlternative.coord)

    val distanceFactor: Double =
      (distance / ZonalParkingManager.AveragePersonWalkingSpeed / ZonalParkingManager.HourInSeconds) * inquiry.valueOfTime

    val parkingCostsPriceFactor: Double = parkingAlternative.costInDollars

    val goingHome: Boolean =
      inquiry.parkingActivityType == ParkingActivityType.Home && parkingAlternative.parkingType == ParkingType.Residential

    val homeActivityPrefersResidentialFactor: Double = if (goingHome) 1.0 else 0.0

    val params: Map[ParkingMNL.Parameters, Double] = Map(
      ParkingMNL.Parameters.RangeAnxietyCost                      -> 0.0,
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
    zone: ParkingZone,
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
    *
    * @param parkingZoneSearchResult ParkingZoneSearchResult
    */
  override protected def processParkingZoneSearchResult(
    inquiry: ParkingInquiry,
    parkingZoneSearchResult: Option[ParkingZoneSearchResult]
  ): Option[ParkingZoneSearchResult] = {
    val output = parkingZoneSearchResult match {
      case Some(result) => result
      case _ =>
        inquiry.parkingActivityType match {
          case ParkingActivityType.Home if inquiry.searchMode != ParkingSearchMode.EnRouteCharging =>
            val newStall = ParkingStall.defaultResidentialStall(inquiry.destinationUtm.loc)
            ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
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
    }
    Some(output)
  }

  /**
    * sample location of a parking stall with a TAZ area
    *
    * @param inquiry     ParkingInquiry
    * @param parkingZone ParkingZone
    * @param taz         TAZ
    */
  override protected def sampleParkingStallLocation(
    inquiry: ParkingInquiry,
    parkingZone: ParkingZone,
    taz: TAZ,
    inClosestZone: Boolean = true
  ): Coord = {
    if (parkingZone.link.isDefined)
      parkingZone.link.get.getCoord
    else if (
      (parkingZone.reservedFor.managerType == VehicleManager.TypeEnum.Household) ||
      (inquiry.parkingActivityType == ParkingActivityType.Home && parkingZone.parkingType == ParkingType.Residential) ||
      (inquiry.parkingActivityType == ParkingActivityType.Work && parkingZone.parkingType == ParkingType.Workplace)
    )
      inquiry.destinationUtm.loc
    else if (tazTreeMap.tazListContainsGeoms) {
      ParkingStallSampling.linkBasedSampling(
        new Random(seed),
        inquiry.destinationUtm.loc,
        tazTreeMap.tazToLinkIdMapping.get(taz.tazId),
        distanceFunction,
        parkingZone.availability,
        taz,
        inClosestZone
      )
    } else {
      ParkingStallSampling.availabilityAwareSampling(
        new Random(seed),
        inquiry.destinationUtm.loc,
        taz,
        parkingZone.availability,
        inClosestZone
      )
    }
  }

  /**
    * Can This Car Park Here
    *
    * @param zone                  ParkingZone
    * @param inquiry               ParkingInquiry
    * @param preferredParkingTypes Set[ParkingType]
    * @return
    */
  protected def canThisCarParkHere(
    zone: ParkingZone,
    inquiry: ParkingInquiry,
    preferredParkingTypes: Set[ParkingType]
  ): Boolean = {
    val validParkingType: Boolean = preferredParkingTypes.contains(zone.parkingType)

    val isValidTime = inquiry.beamVehicle.forall(vehicle =>
      zone.timeRestrictions
        .get(vehicle.beamVehicleType.vehicleCategory)
        .forall(_.contains(inquiry.destinationUtm.time % (24 * 3600)))
    )

    validParkingType && isValidTime
  }

  /**
    * Preferred Parking Types
    *
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getPreferredParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    // a lookup for valid parking types based on this inquiry
    if (inquiry.beamVehicle.exists(v => v.isFreight)) {
      Set(ParkingType.Commercial)
    } else {
      if (inquiry.searchMode == ParkingSearchMode.EnRouteCharging) {
        Set(ParkingType.Public, ParkingType.Commercial)
      } else if (inquiry.searchMode == ParkingSearchMode.Init) {
        inquiry.parkingActivityType match {
          case ParkingActivityType.Home => Set(ParkingType.Residential)
          case ParkingActivityType.Work => Set(ParkingType.Workplace)
          case _                        => Set(ParkingType.Public)
        }
      } else {
        inquiry.parkingActivityType match {
          case ParkingActivityType.Home   => Set(ParkingType.Residential, ParkingType.Public)
          case ParkingActivityType.Work   => Set(ParkingType.Workplace, ParkingType.Public)
          case ParkingActivityType.Charge => Set(ParkingType.Public, ParkingType.Commercial)
          case _                          => Set(ParkingType.Public, ParkingType.Commercial)
        }
      }
    }
  }
}
