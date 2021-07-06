package beam.agentsim.infrastructure

import akka.actor.ActorRef
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.ChargingCapability
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.{DefaultParkingZoneId, UbiqiutousParkingAvailability}
import beam.agentsim.infrastructure.parking.ParkingZoneFileUtils.ParkingLoadingAccumulator
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams,
  ZoneSearchTree
}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.util.{Failure, Random, Success, Try}

class ZonalParkingManager[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  geo: GeoUtils,
  parkingZones: Array[ParkingZone[GEO]],
  zoneSearchTree: ParkingZoneSearch.ZoneSearchTree[GEO],
  rand: Random,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig,
  chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
) extends ParkingNetwork[GEO] {

  if (maxSearchRadius < minSearchRadius) {
    logger.warn(
      s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of $minSearchRadius; no searches will occur with these settings."
    )
  }

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  val functions: ZonalParkingManagerFunctions[GEO] = new ZonalParkingManagerFunctions(
    geoQuadTree,
    idToGeoMapping,
    geoToTAZ,
    geo,
    parkingZones,
    zoneSearchTree,
    rand,
    minSearchRadius,
    maxSearchRadius,
    boundingBox,
    mnlMultiplierParameters,
    chargingPointConfig
  )

  override def processParkingInquiry(
    inquiry: ParkingInquiry,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): Option[ParkingInquiryResponse] = {
    logger.debug("Received parking inquiry: {}", inquiry)

    val ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, parkingZone, _, _, _) =
      functions.searchForParkingStall(inquiry)

    // reserveStall is false when agent is only seeking pricing information
    if (inquiry.reserveStall) {

      logger.debug(
        s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging" else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
      )

      // update the parking stall data
      val claimed: Boolean = ParkingZone.claimStall(parkingZone)
      if (claimed) {
        totalStallsInUse += 1
        totalStallsAvailable -= 1
      }

      logger.debug("Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)

      if (totalStallsInUse % 1000 == 0) logger.debug("Parking stalls in use: {}", totalStallsInUse)
    }

    Some(ParkingInquiryResponse(parkingStall, inquiry.requestId, inquiry.triggerId))
  }

  override def processReleaseParkingStall(release: ReleaseParkingStall) = {
    val parkingZoneId = release.stall.parkingZoneId
    if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
      // this is an infinitely available resource; no update required
      logger.debug("Releasing a stall in the default/emergency zone")
    } else if (parkingZoneId < ParkingZone.DefaultParkingZoneId || parkingZones.length <= parkingZoneId) {
      logger.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
    } else {

      val released: Boolean = ParkingZone.releaseStall(parkingZones(parkingZoneId))
      if (released) {
        totalStallsInUse -= 1
        totalStallsAvailable += 1
      }
    }

    logger.debug("ReleaseParkingStall with {} available stalls ", totalStallsAvailable)
  }

  override def getParkingZones(): Array[ParkingZone[GEO]] = parkingZones
}

class ZonalParkingManagerFunctions[GEO: GeoLevel](
  geoQuadTree: QuadTree[GEO],
  idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
  geoToTAZ: GEO => TAZ,
  geo: GeoUtils,
  parkingZones: Array[ParkingZone[GEO]],
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
    ParkingZone(
      DefaultParkingZoneId,
      GeoLevel[GEO].defaultGeoId,
      ParkingType.Public,
      UbiqiutousParkingAvailability,
      Seq.empty
    )

  def searchForParkingStall(inquiry: ParkingInquiry): ParkingZoneSearch.ParkingZoneSearchResult[GEO] = {
    // a lookup for valid parking types based on this inquiry
    val preferredParkingTypes: Seq[ParkingType] =
      inquiry.activityTypeLowerCased match {
        case act if act.equalsIgnoreCase("home") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("init") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("work") => Seq(ParkingType.Workplace, ParkingType.Public)
        case act if act.equalsIgnoreCase("fast-charge") =>
          Seq(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
        case _ => Seq(ParkingType.Public)
      }

    // allow charger ParkingZones
    val returnSpotsWithChargers: Boolean = inquiry.activityTypeLowerCased match {
      case "fast-charge" => true
      case "init"        => false
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
      case "fast-charge" => false
      case _             => true
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
        rand,
        preferredParkingTypes,
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

  def chargingTypeToNo(parkingZonesSampled: List[(Int, Option[ChargingPointType], ParkingType, Double)]): String = {
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

  def parkingTypeToNo(parkingZonesSampled: List[(Int, Option[ChargingPointType], ParkingType, Double)]): String = {
    parkingZonesSampled
      .map(triple => triple._3)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

  def listOfCosts(parkingZonesSampled: List[(Int, Option[ChargingPointType], ParkingType, Double)]): String = {
    parkingZonesSampled
      .map(triple => triple._4)
      .groupBy(identity)
      .mapValues(_.size)
      .map(x => x._1.toString + ": " + x._2)
      .mkString(", ")
  }

}

object ZonalParkingManager extends LazyLogging {

  // this number should be less than the MaxSearchRadius config value, tuned to being
  // slightly less than the average distance between TAZ centroids.

  val AveragePersonWalkingSpeed: Double = 1.4 // in m/s
  val HourInSeconds: Int = 3600
  val DollarsInCents: Double = 100.0

  /**
    * constructs a ZonalParkingManager with provided parkingZones
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    parkingZones: Array[ParkingZone[GEO]],
    searchTree: ZoneSearchTree[GEO],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): ZonalParkingManager[GEO] = {

    val minSearchRadius = beamConfig.beam.agentsim.agents.parking.minSearchRadius
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius
    val chargingPointConfig = beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint

    val mnlMultiplierParameters = mnlMultiplierParametersFromConfig(beamConfig)

    new ZonalParkingManager(
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      geo,
      parkingZones,
      searchTree,
      random,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      mnlMultiplierParameters,
      chargingPointConfig
    )
  }

  def mnlMultiplierParametersFromConfig(
    beamConfig: BeamConfig
  ): Map[ParkingMNL.Parameters, UtilityFunctionOperation] = {
    val mnlParamsFromConfig = beamConfig.beam.agentsim.agents.parking.mulitnomialLogit.params
    Map(
      ParkingMNL.Parameters.RangeAnxietyCost -> UtilityFunctionOperation.Multiplier(
        mnlParamsFromConfig.rangeAnxietyMultiplier
      ),
      ParkingMNL.Parameters.WalkingEgressCost -> UtilityFunctionOperation.Multiplier(
        mnlParamsFromConfig.distanceMultiplier
      ),
      ParkingMNL.Parameters.ParkingTicketCost -> UtilityFunctionOperation.Multiplier(
        mnlParamsFromConfig.parkingPriceMultiplier
      ),
      ParkingMNL.Parameters.HomeActivityPrefersResidentialParking -> UtilityFunctionOperation.Multiplier(
        mnlParamsFromConfig.homeActivityPrefersResidentialParkingMultiplier
      )
    )
  }

  /**
    * constructs a ZonalParkingManager from file
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    geo: GeoUtils,
    boundingBox: Envelope,
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String]
  ): ZonalParkingManager[GEO] = {

    // generate or load parking
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor

    val random = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }

    val (stalls, searchTree) =
      loadParkingZones(
        parkingFilePath,
        depotFilePaths,
        geoQuadTree,
        parkingStallCountScalingFactor,
        parkingCostScalingFactor,
        random
      )

    ZonalParkingManager(
      beamConfig,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      stalls,
      searchTree,
      geo,
      random,
      boundingBox
    )
  }

  def loadParkingZones[GEO: GeoLevel](
    parkingFilePath: String,
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    random: Random
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    loadParkingZones(
      parkingFilePath,
      IndexedSeq.empty,
      geoQuadTree,
      parkingStallCountScalingFactor,
      parkingCostScalingFactor,
      random
    )
  }

  def loadParkingZones[GEO: GeoLevel](
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String],
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    random: Random
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val initialAccumulator: ParkingLoadingAccumulator[GEO] = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(geoQuadTree.values().asScala, random)
    } else {
      Try {
        ParkingZoneFileUtils.fromFileToAccumulator(
          parkingFilePath,
          random,
          parkingStallCountScalingFactor,
          parkingCostScalingFactor
        )
      } match {
        case Success(accumulator) => accumulator
        case Failure(e) =>
          logger.error(s"unable to read contents of provided parking file $parkingFilePath", e)
          ParkingZoneFileUtils.generateDefaultParkingAccumulatorFromGeoObjects(
            geoQuadTree.values().asScala,
            random
          )
      }
    }
    val parkingLoadingAccumulator = depotFilePaths.foldLeft(initialAccumulator) {
      case (acc, filePath) =>
        filePath.trim match {
          case "" => acc
          case depotParkingFilePath @ _ =>
            Try {
              ParkingZoneFileUtils.fromFileToAccumulator(
                depotParkingFilePath,
                random,
                parkingStallCountScalingFactor,
                parkingCostScalingFactor,
                acc
              )
            } match {
              case Success(accumulator) => accumulator
              case Failure(e) =>
                logger.error(s"unable to read contents of provided parking file $depotParkingFilePath", e)
                acc
            }
        }
    }
    (parkingLoadingAccumulator.zones.toArray, parkingLoadingAccumulator.tree)
  }

  /**
    * constructs a ZonalParkingManager from a string iterator (typically, for testing)
    *
    * @param parkingDescription line-by-line string representation of parking including header
    * @param random             random generator used for sampling parking locations
    * @param includesHeader     true if the parkingDescription includes a csv-style header
    * @return
    */
  def apply[GEO: GeoLevel](
    parkingDescription: Iterator[String],
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    geo: GeoUtils,
    random: Random,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    includesHeader: Boolean = true,
    chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
  ): ZonalParkingManager[GEO] = {
    val parking = ParkingZoneFileUtils.fromIterator(
      parkingDescription,
      random,
      1.0,
      1.0
    )
    new ZonalParkingManager(
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      geo,
      parking.zones.toArray,
      parking.tree,
      random,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      ParkingMNL.DefaultMNLParameters,
      chargingPointConfig
    )
  }

  /**
    * builds a ZonalParkingManager Actor
    *
    * @param beamRouter Actor responsible for routing decisions (deprecated/previously unused)
    * @return
    */
  def init[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    geo: GeoUtils,
    beamRouter: ActorRef,
    boundingBox: Envelope,
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String]
  ): ParkingNetwork[GEO] = {
    ZonalParkingManager(
      beamConfig,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      geo,
      boundingBox,
      parkingFilePath,
      depotFilePaths
    )
  }

  /**
    * builds a ZonalParkingManager Actor with provided parkingZones and geoQuadTree
    *
    * @return
    */
  def init[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    parkingZones: Array[ParkingZone[GEO]],
    searchTree: ZoneSearchTree[GEO],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): ParkingNetwork[GEO] = {
    ZonalParkingManager(
      beamConfig,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      parkingZones,
      searchTree,
      geo,
      random,
      boundingBox
    )
  }
}
