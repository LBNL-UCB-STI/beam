package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.UbiqiutousParkingAvailability
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams
}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

abstract class InfrastructureFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  geo: GeoUtils,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  zoneSearchTree: ParkingZoneSearch.ZoneSearchTree[GEO],
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  rand: Random
) extends StrictLogging {

  /**
    * Generic method for updating MNL Parameters
    * @param parkingAlternative ParkingAlternative
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def updateMNLParameters(
    parkingAlternative: ParkingAlternative[GEO],
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double]

  /**
    * Generic method for adding new search filter to parking zones
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getAdditionalSearchFilterPredicates(zone: ParkingZone[GEO], inquiry: ParkingInquiry): Boolean

  // ************

  import InfrastructureFunctions._

  val DefaultParkingZone: ParkingZone[GEO] =
    ParkingZone.defaultInit(
      GeoLevel[GEO].defaultGeoId,
      ParkingType.Public,
      UbiqiutousParkingAvailability
    )

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      geo.distUTMInMeters
    )

  def searchForParkingStall(inquiry: ParkingInquiry): ParkingZoneSearch.ParkingZoneSearchResult[GEO] = {
    // a lookup for valid parking types based on this inquiry
    val preferredParkingTypes: Set[ParkingType] = inquiry.activityTypeLowerCased match {
      case act if act.equalsIgnoreCase("home") => Set(ParkingType.Residential, ParkingType.Public)
      case act if act.equalsIgnoreCase("init") => Set(ParkingType.Residential, ParkingType.Public)
      case act if act.equalsIgnoreCase("work") => Set(ParkingType.Workplace, ParkingType.Public)
      case act if act.equalsIgnoreCase("charge") =>
        Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
      case _ => Set(ParkingType.Public)
    }

    // ---------------------------------------------------------------------------------------------
    // a ParkingZoneSearch takes the following as parameters
    //
    //   ParkingZoneSearchConfiguration: static settings for all searches
    //   ParkingZoneSearchParams: things specific to this inquiry/state of simulation
    //   parkingZoneFilterFunction: a predicate which is applied as a filter for each search result
    //     which filters out the search case, typically due to ParkingZone/ParkingInquiry fields
    //   parkingZoneLocSamplingFunction: this function creates a ParkingStall from a ParkingZone
    //     by sampling a location for a stall
    //   parkingZoneMNLParamsFunction: this is used to decorate each ParkingAlternative with
    //     utility function parameters. all alternatives are sampled in a multinomial logit function
    //     based on this.
    // ---------------------------------------------------------------------------------------------

    val parkingZoneSearchParams: ParkingZoneSearchParams[GEO] =
      ParkingZoneSearchParams(
        inquiry.destinationUtm.loc,
        inquiry.parkingDuration,
        mnlMultiplierParameters,
        zoneSearchTree,
        parkingZones,
        geoQuadTree,
        rand
      )

    // filters out ParkingZones which do not apply to this agent
    // TODO: check for conflicts between variables here - is it always false?
    val parkingZoneFilterFunction: ParkingZone[GEO] => Boolean =
      (zone: ParkingZone[GEO]) => {
        val canThisCarParkHere = getCanThisCarParkHere(zone, inquiry, preferredParkingTypes)
        val searchFilterPredicates = getAdditionalSearchFilterPredicates(zone, inquiry)
        canThisCarParkHere && searchFilterPredicates
      }

    // generates a coordinate for an embodied ParkingStall from a ParkingZone
    val parkingZoneLocSamplingFunction: ParkingZone[GEO] => Coord =
      (zone: ParkingZone[GEO]) => {
        idToGeoMapping.get(zone.geoId) match {
          case None =>
            logger.error(
              s"somehow have a ParkingZone with geoId ${zone.geoId} which is not found in the idToGeoMapping"
            )
            new Coord()
          case Some(taz) =>
            GeoLevel[GEO].geoSampling(rand, inquiry.destinationUtm.loc, taz, zone.availability)
        }
      }

    // adds multinomial logit parameters to a ParkingAlternative
    val parkingZoneMNLParamsFunction: ParkingAlternative[GEO] => Map[ParkingMNL.Parameters, Double] =
      (parkingAlternative: ParkingAlternative[GEO]) => {
        val defaultParams = getDefaultMNLParameters(parkingAlternative, inquiry)
        val params = defaultParams ++ updateMNLParameters(parkingAlternative, inquiry)
        if (inquiry.activityTypeLowerCased == "home") {
          logger.debug(
            f"tour=${inquiry.remainingTripData
              .map {
                _.remainingTourDistance
              }
              .getOrElse(0.0)}%.2f ${ParkingMNL.prettyPrintAlternatives(params)}"
          )
        }
        params
      }

    ///////////////////////////////////////////
    // run ParkingZoneSearch for a ParkingStall
    ///////////////////////////////////////////
    val result @ ParkingZoneSearch.ParkingZoneSearchResult(
      parkingStall,
      parkingZone,
      parkingZonesSeen,
      parkingZonesSampled,
      iterations
    ) =
      ParkingZoneSearch.incrementalParkingZoneSearch(
        parkingZoneSearchConfiguration,
        parkingZoneSearchParams,
        parkingZoneFilterFunction,
        parkingZoneLocSamplingFunction,
        parkingZoneMNLParamsFunction,
        geoToTAZ,
      ) match {
        case Some(result) =>
          result
        case None =>
          inquiry.activityType match {
            case "init" | "home" =>
              val newStall =
                ParkingStall.defaultResidentialStall(inquiry.destinationUtm.loc, GeoLevel[GEO].defaultGeoId)
              ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
            case _ =>
              // didn't find any stalls, so, as a last resort, create a very expensive stall
              val boxAroundRequest = new Envelope(
                inquiry.destinationUtm.loc.getX + 2000,
                inquiry.destinationUtm.loc.getX - 2000,
                inquiry.destinationUtm.loc.getY + 2000,
                inquiry.destinationUtm.loc.getY - 2000
              )
              val newStall =
                ParkingStall.lastResortStall(
                  boxAroundRequest,
                  rand,
                  tazId = TAZ.EmergencyTAZId,
                  geoId = GeoLevel[GEO].emergencyGeoId
                )
              ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
          }
      }

    logger.debug(
      s"sampled over ${parkingZonesSampled.length} (found ${parkingZonesSeen.length}) parking zones over $iterations iterations."
    )
    logger.debug(
      "sampled stats:\n    ChargerTypes: {};\n    Parking Types: {};\n    Costs: {};",
      chargingTypeToNo(parkingZonesSampled),
      parkingTypeToNo(parkingZonesSampled),
      listOfCosts(parkingZonesSampled)
    )
    result
  }

  /**
    * Can This Car Park Here
    * @param zone ParkingZone
    * @param inquiry ParkingInquiry
    * @param preferredParkingTypes Set[ParkingType]
    * @return
    */
  protected def getCanThisCarParkHere(
    zone: ParkingZone[GEO],
    inquiry: ParkingInquiry,
    preferredParkingTypes: Set[ParkingType]
  ): Boolean = {

    val hasAvailability: Boolean = parkingZones(zone.parkingZoneId).stallsAvailable > 0

    val validParkingType: Boolean = preferredParkingTypes.contains(zone.parkingType)

    val isValidCategory = zone.reservedFor.isEmpty || inquiry.beamVehicle.forall(
      vehicle => zone.reservedFor.contains(vehicle.beamVehicleType.vehicleCategory)
    )

    val isValidTime = inquiry.beamVehicle.forall(
      vehicle =>
        zone.timeRestrictions
          .get(vehicle.beamVehicleType.vehicleCategory)
          .forall(_.contains(inquiry.destinationUtm.time % (24 * 3600)))
    )

    val isValidVehicleManager = inquiry.beamVehicle.forall { vehicle =>
      zone.vehicleManager.isEmpty || vehicle.vehicleManager == zone.vehicleManager
    }

    hasAvailability & validParkingType & isValidCategory & isValidTime & isValidVehicleManager
  }

  /**
    * Default MNL Parameters
    * @param parkingAlternative ParkingAlternative
    * @param inquiry ParkingInquiry
    * @return
    */
  protected def getDefaultMNLParameters(
    parkingAlternative: ParkingAlternative[GEO],
    inquiry: ParkingInquiry
  ): Map[ParkingMNL.Parameters, Double] = {

    val distance: Double = geo.distUTMInMeters(inquiry.destinationUtm.loc, parkingAlternative.coord)

    val distanceFactor
      : Double = (distance / ZonalParkingManager.AveragePersonWalkingSpeed / ZonalParkingManager.HourInSeconds) * inquiry.valueOfTime

    val parkingCostsPriceFactor: Double = parkingAlternative.costInDollars

    val goingHome
      : Boolean = inquiry.activityTypeLowerCased == "home" && parkingAlternative.parkingType == ParkingType.Residential

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
}

object InfrastructureFunctions {

  def chargingTypeToNo(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(
        triple =>
          triple._2 match {
            case Some(x) => x
            case None    => "NoCharger"
        }
      )
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

  def parkingTypeToNo(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(triple => triple._3)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

  def listOfCosts(
    parkingZonesSampled: List[(Id[ParkingZoneId], Option[ChargingPointType], ParkingType, Double)]
  ): String = {
    parkingZonesSampled
      .map(triple => triple._4)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

}
