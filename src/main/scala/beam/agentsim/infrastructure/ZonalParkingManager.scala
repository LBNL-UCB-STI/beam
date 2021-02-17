package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.{DefaultParkingZoneId, UbiqiutousParkingAvailability}
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
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.util.{Failure, Random, Success, Try}
import scala.collection.JavaConverters._

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
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig
) extends beam.utils.CriticalActor
    with ActorLogging {

  if (maxSearchRadius < minSearchRadius) {
    log.warning(
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
  )

  override def receive: Receive = {

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      val ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, parkingZone, _, _, _) =
        functions.searchForParkingStall(inquiry)

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {

        log.debug(
          s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging" else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
        )

        // update the parking stall data
        val claimed: Boolean = ParkingZone.claimStall(parkingZone)
        if (claimed) {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
        }

        log.debug("Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)

        if (totalStallsInUse % 1000 == 0) log.debug("Parking stalls in use: {}", totalStallsInUse)
      }

      sender() ! ParkingInquiryResponse(parkingStall, inquiry.requestId)

    case ReleaseParkingStall(parkingZoneId, _) =>
      if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
        if (log.isDebugEnabled) {
          // this is an infinitely available resource; no update required
          log.debug("Releasing a stall in the default/emergency zone")
        }
      } else if (parkingZoneId < ParkingZone.DefaultParkingZoneId || parkingZones.length <= parkingZoneId) {
        if (log.isDebugEnabled) {
          log.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
        }
      } else {

        val released: Boolean = ParkingZone.releaseStall(parkingZones(parkingZoneId))
        if (released) {
          totalStallsInUse -= 1
          totalStallsAvailable += 1
        }
      }
      if (log.isDebugEnabled) {
        log.debug("ReleaseParkingStall with {} available stalls ", totalStallsAvailable)
      }
  }
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
      None,
      None
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
        inquiry.destinationUtm,
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

        hasAvailability &&
        rideHailFastChargingOnly &&
        validParkingType &&
        canThisCarParkHere
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
            GeoLevel[GEO].geoSampling(rand, inquiry.destinationUtm, taz, zone.availability)
        }
      }

    // adds multinomial logit parameters to a ParkingAlternative
    val parkingZoneMNLParamsFunction: ParkingAlternative[GEO] => Map[ParkingMNL.Parameters, Double] =
      (parkingAlternative: ParkingAlternative[GEO]) => {

        val distance: Double = geo.distUTMInMeters(inquiry.destinationUtm, parkingAlternative.coord)

        // end-of-day parking durations are set to zero, which will be mis-interpreted here
        val parkingDuration: Option[Long] =
          if (inquiry.parkingDuration.toLong <= 0L) None
          else Some(inquiry.parkingDuration.toLong)

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
              val newStall = ParkingStall.defaultResidentialStall(inquiry.destinationUtm, GeoLevel[GEO].defaultGeoId)
              ParkingZoneSearch.ParkingZoneSearchResult(newStall, DefaultParkingZone)
            case _ =>
              // didn't find any stalls, so, as a last resort, create a very expensive stall
              val boxAroundRequest = new Envelope(
                inquiry.destinationUtm.getX + 2000,
                inquiry.destinationUtm.getX - 2000,
                inquiry.destinationUtm.getY + 2000,
                inquiry.destinationUtm.getY - 2000
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
      mnlMultiplierParameters
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
    boundingBox: Envelope
  ): ZonalParkingManager[GEO] = {

    // generate or load parking
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor

    val random = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }

    val (stalls, searchTree) =
      loadParkingZones(parkingFilePath, geoQuadTree, parkingStallCountScalingFactor, parkingCostScalingFactor, random)

    ZonalParkingManager(
      beamConfig,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      stalls,
      searchTree,
      geo,
      random,
      boundingBox,
    )
  }

  def loadParkingZones[GEO: GeoLevel](
    parkingFilePath: String,
    geoQuadTree: QuadTree[GEO],
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    random: Random
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val (stalls, searchTree) = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingFromGeoObjects(geoQuadTree.values().asScala, random)
    } else {
      Try {
        ParkingZoneFileUtils.fromFile(parkingFilePath, random, parkingStallCountScalingFactor, parkingCostScalingFactor)
      } match {
        case Success((s, t)) => (s, t)
        case Failure(e) =>
          logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
          ParkingZoneFileUtils.generateDefaultParkingFromGeoObjects(geoQuadTree.values().asScala, random)
      }
    }
    (stalls, searchTree)
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
    includesHeader: Boolean = true
  ): ZonalParkingManager[GEO] = {
    val parking = ParkingZoneFileUtils.fromIterator(parkingDescription, random, 1.0, 1.0, true)
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
      ParkingMNL.DefaultMNLParameters
    )
  }

  /**
    * builds a ZonalParkingManager Actor
    *
    * @param beamRouter Actor responsible for routing decisions (deprecated/previously unused)
    * @return
    */
  def props[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    geo: GeoUtils,
    beamRouter: ActorRef,
    boundingBox: Envelope
  ): Props = {

    Props(
      ZonalParkingManager(
        beamConfig,
        geoQuadTree,
        idToGeoMapping,
        geoToTAZ,
        geo,
        boundingBox
      )
    )
  }

  /**
    * builds a ZonalParkingManager Actor with provided parkingZones and geoQuadTree
    *
    * @return
    */
  def props[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    parkingZones: Array[ParkingZone[GEO]],
    searchTree: ZoneSearchTree[GEO],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): Props = {

    Props(
      ZonalParkingManager(
        beamConfig,
        geoQuadTree,
        idToGeoMapping,
        geoToTAZ,
        parkingZones,
        searchTree,
        geo,
        random,
        boundingBox,
      )
    )
  }
}
