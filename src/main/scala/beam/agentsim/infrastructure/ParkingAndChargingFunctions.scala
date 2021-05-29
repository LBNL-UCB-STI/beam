package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.ChargingCapability
import beam.agentsim.agents.vehicles.FuelType.Electricity
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
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ParkingAndChargingFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  geo: GeoUtils,
  parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
  zoneSearchTree: ParkingZoneSearch.ZoneSearchTree[GEO],
  rand: Random,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig,
  chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
) extends StrictLogging {

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      geo.distUTMInMeters
    )

  val DefaultParkingZone: ParkingZone[GEO] =
    ParkingZone.defaultInit(
      GeoLevel[GEO].defaultGeoId,
      ParkingType.Public,
      UbiqiutousParkingAvailability
    )

  def searchForParkingStall(inquiry: ParkingInquiry): ParkingZoneSearch.ParkingZoneSearchResult[GEO] = {
    // a lookup for valid parking types based on this inquiry
    val preferredParkingTypes: Set[ParkingType] =
      inquiry.activityTypeLowerCased match {
        case act if act.equalsIgnoreCase("home") => Set(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("init") => Set(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("work") => Set(ParkingType.Workplace, ParkingType.Public)
        case act if act.equalsIgnoreCase("charge") =>
          Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
        case _ => Set(ParkingType.Public)
      }

    // allow charger ParkingZones
    val returnSpotsWithChargers: Boolean = inquiry.activityTypeLowerCased match {
      case "charge" => true
      case "init"   => false
      case _ =>
        inquiry.beamVehicle match {
          case Some(vehicleType) =>
            vehicleType.beamVehicleType.primaryFuelType match {
              case Electricity => true
              case _           => false
            }
          case _ => false
        }
    }

    // allow non-charger ParkingZones
    val returnSpotsWithoutChargers: Boolean = inquiry.activityTypeLowerCased match {
      case "charge" => false
      case _        => true
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

        val hasAvailability: Boolean = parkingZones(zone.parkingZoneId).stallsAvailable > 0

        val rideHailFastChargingOnly: Boolean =
          ParkingSearchFilterPredicates.rideHailFastChargingOnly(
            zone,
            inquiry.activityType
          )

        val canThisCarParkHere: Boolean =
          ParkingSearchFilterPredicates.canThisCarParkHere(
            zone,
            returnSpotsWithChargers,
            returnSpotsWithoutChargers
          )

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

        val validChargingCapability = inquiry.beamVehicle.forall(
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

        hasAvailability &&
        rideHailFastChargingOnly &&
        validParkingType &&
        canThisCarParkHere &&
        isValidCategory &&
        isValidTime &&
        isValidVehicleManager &&
        validChargingCapability
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

        val distance: Double = geo.distUTMInMeters(inquiry.destinationUtm.loc, parkingAlternative.coord)

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

        val distanceFactor
          : Double = (distance / ZonalParkingManager.AveragePersonWalkingSpeed / ZonalParkingManager.HourInSeconds) * inquiry.valueOfTime

        val parkingCostsPriceFactor: Double = parkingAlternative.costInDollars

        val goingHome
          : Boolean = inquiry.activityTypeLowerCased == "home" && parkingAlternative.parkingType == ParkingType.Residential
        val chargingVehicle: Boolean = inquiry.beamVehicle match {
          case Some(beamVehicle) =>
            beamVehicle.beamVehicleType.primaryFuelType match {
              case Electricity =>
                true
              case _ => false
            }
          case None => false
        }
        val chargingStall: Boolean = parkingAlternative.parkingZone.chargingPointType.nonEmpty

        val homeActivityPrefersResidentialFactor: Double =
          if (chargingVehicle) {
            if (goingHome && chargingStall) 1.0 else 0.0
          } else {
            if (goingHome) 1.0 else 0.0
          }

        val params: Map[ParkingMNL.Parameters, Double] = new Map.Map4(
          key1 = ParkingMNL.Parameters.RangeAnxietyCost,
          value1 = rangeAnxietyFactor,
          key2 = ParkingMNL.Parameters.WalkingEgressCost,
          value2 = distanceFactor,
          key3 = ParkingMNL.Parameters.ParkingTicketCost,
          value3 = parkingCostsPriceFactor,
          key4 = ParkingMNL.Parameters.HomeActivityPrefersResidentialParking,
          value4 = homeActivityPrefersResidentialFactor
        )

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
