package beam.agentsim.infrastructure

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
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

    val params: Map[ParkingMNL.Parameters, Double] = new Map.Map4(
      key1 = ParkingMNL.Parameters.RangeAnxietyCost,
      value1 = 0.0,
      key2 = ParkingMNL.Parameters.WalkingEgressCost,
      value2 = distanceFactor,
      key3 = ParkingMNL.Parameters.ParkingTicketCost,
      value3 = parkingCostsPriceFactor,
      key4 = ParkingMNL.Parameters.HomeActivityPrefersResidentialParking,
      value4 = homeActivityPrefersResidentialFactor
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

    hasAvailability & validParkingType & isValidTime & isValidVehicleManager
  }

  /**
    * Preferred Parking Types
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getPreferredParkingTypes(inquiry: ParkingInquiry): Set[ParkingType] = {
    // a lookup for valid parking types based on this inquiry
    inquiry.parkingActivityType match {
      case ParkingActivityType.Home   => Set(ParkingType.Residential, ParkingType.Public)
      case ParkingActivityType.Init   => Set(ParkingType.Residential, ParkingType.Public)
      case ParkingActivityType.Work   => Set(ParkingType.Workplace, ParkingType.Public)
      case ParkingActivityType.Charge => Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
      case _                          => Set(ParkingType.Public)
    }
  }

}

object ParkingFunctions {

  def mnlMultiplierParametersFromConfig(
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit
  ): Map[ParkingMNL.Parameters, UtilityFunctionOperation] = {
    Map(
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
      )
    )
  }
}
