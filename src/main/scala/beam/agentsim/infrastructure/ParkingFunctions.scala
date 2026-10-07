package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleUse.Freight
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingSearchMode.DoubleParkingAllowed
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.VehicleRestrictionKey
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchResult}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Parking
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ParkingFunctions(
  tazTreeMap: TAZTreeMap,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  distanceFunction: (Coord, Coord) => Double,
  searchRadiusConfig: BeamConfig.Beam.Agentsim.Agents.Parking.Search.Params,
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
      searchRadiusConfig,
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
    ParkingMNL.Parameters.ParkingTypePreference -> UtilityFunctionOperation.Multiplier(
      mnlParkingConfig.params.parkingTypePreferenceMultiplier
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

    val parkingTypePreferences = getPreferredParkingTypes(inquiry)
    val parkingTypePreferenceFactor: Double =
      if (parkingTypePreferences.contains(parkingAlternative.parkingType)) 1.0 else 0.0

    val params: Map[ParkingMNL.Parameters, Double] = Map(
      ParkingMNL.Parameters.RangeAnxietyCost      -> 0.0,
      ParkingMNL.Parameters.WalkingEgressCost     -> distanceFactor,
      ParkingMNL.Parameters.ParkingTicketCost     -> parkingCostsPriceFactor,
      ParkingMNL.Parameters.ParkingTypePreference -> parkingTypePreferenceFactor,
      ParkingMNL.Parameters.EnrouteDetourCost     -> 0.0
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

    val allowedParkingTypes = getAllowedParkingTypes(inquiry)
    val canCarParkHere: Boolean = canThisCarParkHere(zone, inquiry, allowedParkingTypes)
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
      case _
          if inquiry.searchMode == DoubleParkingAllowed && searchRadiusConfig.searchDoubleParkingRadius > 0 && inquiry.vehicleUse == Freight =>
        val (newStall, parkingZone) = ParkingStall.obstructiveStallAtLocation(
          inquiry.destinationUtm.loc,
          tazTreeMap.getTAZ(inquiry.destinationUtm.loc).tazId,
          inquiry.parkingActivityType
        )
        ParkingZoneSearch.ParkingZoneSearchResult(newStall, parkingZone)
      case _ if inquiry.parkingActivityType == ParkingActivityType.Home =>
        val (newStall, zone) = ParkingStall.defaultStall(
          inquiry.destinationUtm.loc,
          tazTreeMap.getTAZ(inquiry.destinationUtm.loc).tazId,
          ParkingType.Residential,
          inquiry.parkingActivityType,
          costInDollars = 0.0
        )
        ParkingZoneSearch.ParkingZoneSearchResult(newStall, zone)
      case _ =>
        // didn't find any stalls, so, as a last resort, create a very expensive stall
        val (newStall, zone) =
          ParkingStall.lastResortStall(inquiry.destinationUtm.loc, new Random(seed), inquiry.parkingActivityType)
        ParkingZoneSearch.ParkingZoneSearchResult(newStall, zone)
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
    linkQuadTree: Option[QuadTree[Link]],
    inClosestZone: Boolean = true
  ): (Coord, Option[Link]) = {
    if (parkingZone.link.isDefined)
      (parkingZone.link.get.getCoord, parkingZone.link)
    else {
      val availability = parkingZone.availability
      if (linkQuadTree.nonEmpty || tazTreeMap.tazListContainsGeoms) {
        ParkingStallSampling.linkBasedSampling(
          new Random(seed),
          inquiry.destinationUtm.loc,
          tazTreeMap.tazToLinkIdMapping.get(taz.tazId),
          distanceFunction,
          availability,
          taz,
          inClosestZone
        )
      } else {
        val coord: Coord = ParkingStallSampling.availabilityAwareSampling(
          new Random(seed),
          inquiry.destinationUtm.loc,
          taz,
          availability,
          inClosestZone
        )
        (coord, None)
      }
    }
  }

  /**
    * Can This Car Park Here
    *
    * @param zone                  ParkingZone
    * @param inquiry               ParkingInquiry
    * @param allowedParkingTypes Set[ParkingType]
    * @return
    */
  protected def canThisCarParkHere(
    zone: ParkingZone,
    inquiry: ParkingInquiry,
    allowedParkingTypes: Set[ParkingType]
  ): Boolean = {
    val validParkingType: Boolean = allowedParkingTypes.contains(zone.parkingType)

    val isValidTime = validParkingType && {
      val vehicleCategory = inquiry.beamVehicle.map(_.beamVehicleType.vehicleCategory)
      val vehicleUse = inquiry.vehicleUse
      val currentTime = inquiry.destinationUtm.time % (24 * 3600)

      var hasActiveRestriction = false
      var matchesActiveRestriction = false
      val restrictions = zone.timeRestrictions.iterator
      while (restrictions.hasNext && !matchesActiveRestriction) {
        val (key, range) = restrictions.next()
        if (range.contains(currentTime)) {
          hasActiveRestriction = true
          matchesActiveRestriction = key match {
            case VehicleRestrictionKey.CategoryAndUse(cat, use) =>
              vehicleCategory.contains(cat) && use == vehicleUse
            case VehicleRestrictionKey.CategoryOnly(cat) =>
              vehicleCategory.contains(cat)
            case VehicleRestrictionKey.UseOnly(use) =>
              use == vehicleUse
          }
        }
      }

      !hasActiveRestriction || matchesActiveRestriction
    }

    val isValidManager =
      inquiry.beamVehicle.forall { vehicle =>
        zone.reservedFor == VehicleManager.AnyManager || vehicle.vehicleManagerId.get() == zone.reservedFor.managerId
      }

    isValidTime && isValidManager
  }

  /**
    * Preferred Parking Types
    *
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getAllowedParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    // a lookup for valid parking types based on this inquiry
    inquiry.parkingActivityType match {
      case Home              => Set(ParkingType.Residential, ParkingType.Public, ParkingType.Commercial)
      case Working           => Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Commercial)
      case FreightOperations => Set(ParkingType.Public, ParkingType.Commercial)
      case FreightDepot      => Set(ParkingType.Depot, ParkingType.Commercial, ParkingType.Public)
      case _                 => Set(ParkingType.Public, ParkingType.Commercial)
    }
  }

  // This methods is used by ChargingFunctions as well
  // it is related to beam.agentsim.agents.parking.multinomialLogit.params.parkingTypePreferenceMultiplier
  private def getPreferredParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    // a lookup for valid parking types based on this inquiry
    if (inquiry.searchMode == ParkingSearchMode.EnRouteCharging) {
      inquiry.parkingActivityType match {
        case FreightOperations => Set(ParkingType.Commercial)
        case FreightDepot      => Set(ParkingType.Commercial)
        case _                 => Set(ParkingType.Public)
      }
    } else if (inquiry.searchMode == ParkingSearchMode.Init) {
      inquiry.parkingActivityType match {
        case Home         => Set(ParkingType.Residential)
        case Working      => Set(ParkingType.Workplace)
        case FreightDepot => Set(ParkingType.Depot)
        case _            => Set(ParkingType.Public)
      }
    } else {
      inquiry.parkingActivityType match {
        case Home              => Set(ParkingType.Residential)
        case Working           => Set(ParkingType.Workplace)
        case FreightOperations => Set(ParkingType.Commercial)
        case FreightDepot      => Set(ParkingType.Depot)
        case Charging          => Set(ParkingType.Depot)
        case _                 => Set(ParkingType.Public)
      }
    }
  }
}
