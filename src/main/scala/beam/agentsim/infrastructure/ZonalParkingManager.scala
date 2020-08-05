package beam.agentsim.infrastructure

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams,
  ZoneSearchTree
}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.{Failure, Random, Success, Try}

class ZonalParkingManager(
  tazTreeMap: TAZTreeMap,
  geo: GeoUtils,
  parkingZones: Array[ParkingZone],
  zoneSearchTree: ParkingZoneSearch.ZoneSearchTree[TAZ],
  emergencyTAZId: Id[TAZ],
  rand: Random,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  mnlMultiplierParameters: ParkingMNL.ParkingMNLConfig
) extends beam.utils.CriticalActor
    with ActorLogging {

  if (maxSearchRadius < minSearchRadius) {
    log.warning(
      s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of ${minSearchRadius}; no searches will occur with these settings."
    )
  }

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      geo.distUTMInMeters
    )

  override def receive: Receive = {

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      // a lookup for valid parking types based on this inquiry
      val preferredParkingTypes: Set[ParkingType] =
        inquiry.activityType.toLowerCase match {
          case act if act.equalsIgnoreCase("home") => Set(ParkingType.Residential, ParkingType.Public)
          case act if act.equalsIgnoreCase("init") => Set(ParkingType.Residential, ParkingType.Public)
          case act if act.equalsIgnoreCase("work") => Set(ParkingType.Workplace, ParkingType.Public)
          case act if act.equalsIgnoreCase("charge") =>
            Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
          case _ => Set(ParkingType.Public)
        }

      // allow charger ParkingZones
      val returnSpotsWithChargers: Boolean = inquiry.activityType.toLowerCase match {
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
      val returnSpotsWithoutChargers: Boolean = inquiry.activityType.toLowerCase match {
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

      val parkingZoneSearchParams: ParkingZoneSearchParams =
        ParkingZoneSearchParams(
          inquiry.destinationUtm,
          inquiry.parkingDuration,
          mnlMultiplierParameters,
          zoneSearchTree,
          parkingZones,
          tazTreeMap.tazQuadTree,
          rand
        )

      // filters out ParkingZones which do not apply to this agent
      // TODO: check for conflicts between variables here - is it always false?
      val parkingZoneFilterFunction: ParkingZone => Boolean =
        (zone: ParkingZone) => {

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
      val parkingZoneLocSamplingFunction: ParkingZone => Coord =
        (zone: ParkingZone) => {
          tazTreeMap.getTAZ(zone.tazId) match {
            case None =>
              log.error(s"somehow have a ParkingZone with tazId ${zone.tazId} which is not found in the TAZTreeMap")
              TAZ.DefaultTAZ.coord
            case Some(taz) =>
              ParkingStallSampling.availabilityAwareSampling(rand, inquiry.destinationUtm, taz, zone.availability)
          }
        }

      // adds multinomial logit parameters to a ParkingAlternative
      val parkingZoneMNLParamsFunction: ParkingAlternative => Map[ParkingMNL.Parameters, Double] =
        (parkingAlternative: ParkingAlternative) => {

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
            : Boolean = inquiry.activityType.toLowerCase == "home" && parkingAlternative.parkingType == ParkingType.Residential
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

          val params: Map[ParkingMNL.Parameters, Double] = Map(
            ParkingMNL.Parameters.RangeAnxietyCost                      -> rangeAnxietyFactor,
            ParkingMNL.Parameters.WalkingEgressCost                     -> distanceFactor,
            ParkingMNL.Parameters.ParkingTicketCost                     -> parkingCostsPriceFactor,
            ParkingMNL.Parameters.HomeActivityPrefersResidentialParking -> homeActivityPrefersResidentialFactor
          )

          if (log.isDebugEnabled && inquiry.activityType.toLowerCase == "home") {
            log.debug(
              f"tour=${inquiry.remainingTripData.map { _.remainingTourDistance }.getOrElse(0.0)}%.2f ${ParkingMNL.prettyPrintAlternatives(params)}"
            )
          }

          params
        }

      ///////////////////////////////////////////
      // run ParkingZoneSearch for a ParkingStall
      ///////////////////////////////////////////
      val ParkingZoneSearch.ParkingZoneSearchResult(
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
          parkingZoneMNLParamsFunction
        ) match {
          case Some(result) =>
            result
          case None =>
            inquiry.activityType match {
              case "init" =>
                val newStall = ParkingStall.defaultResidentialStall(inquiry.destinationUtm)
                ParkingZoneSearch.ParkingZoneSearchResult(newStall, ParkingZone.DefaultParkingZone)
              case "home" =>
                val newStall = ParkingStall.defaultResidentialStall(inquiry.destinationUtm)
                ParkingZoneSearch.ParkingZoneSearchResult(newStall, ParkingZone.DefaultParkingZone)
              case _ =>
                // didn't find any stalls, so, as a last resort, create a very expensive stall
                val boxAroundRequest = new Envelope(
                  inquiry.destinationUtm.getX + 2000,
                  inquiry.destinationUtm.getX - 2000,
                  inquiry.destinationUtm.getY + 2000,
                  inquiry.destinationUtm.getY - 2000
                )
                val newStall = ParkingStall.lastResortStall(boxAroundRequest, rand, tazId = emergencyTAZId)
                ParkingZoneSearch.ParkingZoneSearchResult(newStall, ParkingZone.DefaultParkingZone)
            }
        }

      log.debug(
        s"sampled over ${parkingZonesSampled.length} (found ${parkingZonesSeen.length}) parking zones over $iterations iterations."
      )
      log.debug(
        s"sampled stats:\n    ChargerTypes: {};\n    Parking Types: {};\n    Costs: {};",
        chargingTypeToNo(parkingZonesSampled),
        parkingTypeToNo(parkingZonesSampled),
        listOfCosts(parkingZonesSampled)
      )

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {

        log.debug(
          s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging" else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
        )

        // update the parking stall data
        val claimed: Boolean = ParkingZone.claimStall(parkingZone).value
        if (claimed) {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
        }

        log.debug(s"Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)

        if (totalStallsInUse % 1000 == 0) log.debug(s"Parking stalls in use: {}", totalStallsInUse)
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

        val released: Boolean = ParkingZone.releaseStall(parkingZones(parkingZoneId)).value
        if (released) {
          totalStallsInUse -= 1
          totalStallsAvailable += 1
        }
      }
      if (log.isDebugEnabled) {
        log.debug("ReleaseParkingStall with {} available stalls ", totalStallsAvailable)
      }
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
  def apply(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    parkingZones: Array[ParkingZone],
    searchTree: ZoneSearchTree[TAZ],
    emergencyTAZId: Id[TAZ],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): ZonalParkingManager = {

    val minSearchRadius = beamConfig.beam.agentsim.agents.parking.minSearchRadius
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius
    val mnlParamsFromConfig = beamConfig.beam.agentsim.agents.parking.mulitnomialLogit.params
    // distance to walk to the destination

    val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation] = Map(
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

    new ZonalParkingManager(
      tazTreeMap,
      geo,
      parkingZones,
      searchTree,
      emergencyTAZId,
      random,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      mnlMultiplierParameters
    )
  }

  /**
    * constructs a ZonalParkingManager from file
    *
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    boundingBox: Envelope
  ): ZonalParkingManager = {

    // generate or load parking
    val parkingFilePath: String = beamConfig.beam.agentsim.taz.parkingFilePath
    val filePath: String = beamConfig.beam.agentsim.taz.filePath
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor

    val random = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }

    val (stalls, searchTree) =
      loadParkingZones(parkingFilePath, filePath, parkingStallCountScalingFactor, parkingCostScalingFactor, random)

    ZonalParkingManager(
      beamConfig,
      tazTreeMap,
      stalls,
      searchTree,
      TAZ.EmergencyTAZId,
      geo,
      random,
      boundingBox,
    )
  }

  def loadParkingZones(
    parkingFilePath: String,
    tazFilePath: String,
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    random: Random
  ): (Array[ParkingZone], ZoneSearchTree[TAZ]) = {
    val (stalls, searchTree) = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingFromTazfile(tazFilePath, random)
    } else {
      Try {
        ParkingZoneFileUtils.fromFile(parkingFilePath, random, parkingStallCountScalingFactor, parkingCostScalingFactor)
      } match {
        case Success((s, t)) => (s, t)
        case Failure(e) =>
          logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
          ParkingZoneFileUtils.generateDefaultParkingFromTazfile(tazFilePath, random)
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
  def apply(
    parkingDescription: Iterator[String],
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    random: Random,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    includesHeader: Boolean = true
  ): ZonalParkingManager = {
    val parking = ParkingZoneFileUtils.fromIterator(parkingDescription, random, 1.0, 1.0, true)
    new ZonalParkingManager(
      tazTreeMap,
      geo,
      parking.zones,
      parking.tree,
      TAZ.EmergencyTAZId,
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
  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    beamRouter: ActorRef,
    boundingBox: Envelope
  ): Props = {

    Props(
      ZonalParkingManager(
        beamConfig,
        tazTreeMap,
        geo,
        boundingBox
      )
    )
  }

  /**
    * builds a ZonalParkingManager Actor with provided parkingZones and taz tree map
    *
    * @return
    */
  def props(
    beamConfig: BeamConfig,
    tazTreeMap: TAZTreeMap,
    parkingZones: Array[ParkingZone],
    searchTree: ZoneSearchTree[TAZ],
    emergencyTAZId: Id[TAZ],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): Props = {

    Props(
      ZonalParkingManager(
        beamConfig,
        tazTreeMap,
        parkingZones,
        searchTree,
        emergencyTAZId,
        geo,
        random,
        boundingBox,
      )
    )
  }
}
