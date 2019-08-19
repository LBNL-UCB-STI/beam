package beam.agentsim.infrastructure

import scala.util.{Failure, Random, Success, Try}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{ParkingAlternative, ParkingZoneSearchConfiguration, ParkingZoneSearchParams}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord

class ZonalParkingManager(
  tazTreeMap: TAZTreeMap,
  geo: GeoUtils,
  parkingZones: Array[ParkingZone],
  zoneSearchTree: ParkingZoneSearch.ZoneSearchTree[TAZ],
  rand: Random,
  maxSearchRadius: Double,
  probabilityOfResidentialCharging: Double,
  boundingBox: Envelope
) extends Actor
    with ActorLogging {

  if (maxSearchRadius < ZonalParkingManager.MinSearchRadius) {
    log.warning(
      s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of ${ZonalParkingManager.MinSearchRadius}; no searches will occur with these settings."
    )
  }

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      ZonalParkingManager.MinSearchRadius,
      maxSearchRadius,
      boundingBox,
      geo.distUTMInMeters
    )

  override def receive: Receive = {

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      // a lookup for valid parking types based on this inquiry
      val preferredParkingTypes: Set[ParkingType] =
        inquiry.activityType match {
          case act if act.equalsIgnoreCase("home") => Set(ParkingType.Residential, ParkingType.Public)
          case act if act.equalsIgnoreCase("init") => Set(ParkingType.Residential, ParkingType.Public)
          case act if act.equalsIgnoreCase("work") => Set(ParkingType.Workplace, ParkingType.Public)
          case act if act.equalsIgnoreCase("charge") =>
            Set(ParkingType.Workplace, ParkingType.Public, ParkingType.Residential)
          case _ => Set(ParkingType.Public)
        }

      // if headed home, some agents require home charging at some probability
      val isPEVAndNeedsToChargeAtHome: Option[Boolean] =
        inquiry.activityType.toLowerCase match {
          case "home" => Some{ rand.nextDouble() <= probabilityOfResidentialCharging }
          case _ => None
        }

      // allow charger ParkingZones
      val returnSpotsWithChargers: Boolean = inquiry.activityType.toLowerCase match {
        case "charge" => true
        case "init"   => false
        case _ =>
          inquiry.vehicleType match {
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
          inquiry.utilityFunction,
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

          val chargeWhenHeadedHome: Boolean =
            ParkingSearchFilterPredicates.testPEVChargeWhenHeadedHome(
              zone,
              isPEVAndNeedsToChargeAtHome,
              inquiry.vehicleType
            )

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
            chargeWhenHeadedHome &&
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
      val parkingZoneMNLParamsFunction: ParkingAlternative => Map[String, Double] =
        (parkingAlternative: ParkingAlternative) => {
          val installedCapacity = parkingAlternative.parkingZone.chargingPointType match {
            case Some(chargingPoint) => ChargingPointType.getChargingPointInstalledPowerInKw(chargingPoint)
            case None                => 0
          }

          val distance: Double = geo.distUTMInMeters(inquiry.destinationUtm, parkingAlternative.coord)
          //val chargingCosts = (39 + random.nextInt((79 - 39) + 1)) / 100d // in $/kWh, assumed price range is $0.39 to $0.79 per kWh

          val averagePersonWalkingSpeed = 1.4 // in m/s
          val hourInSeconds = 3600
          val maxAssumedInstalledChargingCapacity = 350 // in kW
          val dollarsInCents = 100

          Map(
            //"energyPriceFactor" -> chargingCosts, //currently assumed that these costs are included into parkingCostsPriceFactor
            "distanceFactor"          -> (distance / averagePersonWalkingSpeed / hourInSeconds) * inquiry.valueOfTime, // in US$
            "installedCapacity"       -> (installedCapacity / maxAssumedInstalledChargingCapacity) * (inquiry.parkingDuration / hourInSeconds) * inquiry.valueOfTime, // in US$ - assumption/untested parkingDuration in seconds
            "parkingCostsPriceFactor" -> parkingAlternative.cost / dollarsInCents //in US$, assumptions for now: parking ticket costs include charging
          )
        }

      ///////////////////////////////////////////
      // run ParkingZoneSearch for a ParkingStall
      ///////////////////////////////////////////
      val ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, parkingZone, parkingZonesSeen, iterations) =
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
            // didn't find any stalls, so, as a last resort, create a very expensive stall
            val newStall = ParkingStall.lastResortStall(boundingBox, rand)
            ParkingZoneSearch.ParkingZoneSearchResult(newStall, ParkingZone.DefaultParkingZone)
        }

      log.debug(s"found ${parkingZonesSeen.length} parking zones over $iterations iterations")

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {

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

    case ReleaseParkingStall(parkingZoneId) =>
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
}

object ZonalParkingManager extends LazyLogging {

  // this number should be less than the MaxSearchRadius config value, tuned to being
  // slightly less than the average distance between TAZ centroids.
  val MinSearchRadius: Double = 1000.0

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
    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor
    val probabilityOfResidentialParking = beamConfig.beam.agentsim.taz.probabilityOfResidentialCharging
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius

    val random = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }

    val (stalls, searchTree) = if (parkingFilePath.isEmpty) {
      ParkingZoneFileUtils.generateDefaultParkingFromTazfile(beamConfig.beam.agentsim.taz.filePath)
    } else {
      Try {
        ParkingZoneFileUtils.fromFile(parkingFilePath, parkingStallCountScalingFactor, parkingCostScalingFactor)
      } match {
        case Success((s, t)) => (s, t)
        case Failure(e) =>
          logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
          ParkingZoneFileUtils.generateDefaultParkingFromTazfile(beamConfig.beam.agentsim.taz.filePath)
      }
    }


    new ZonalParkingManager(
      tazTreeMap,
      geo,
      stalls,
      searchTree,
      random,
      maxSearchRadius,
      probabilityOfResidentialParking,
      boundingBox
    )
  }

  /**
    * constructs a ZonalParkingManager from a string iterator
    *
    * @param parkingDescription line-by-line string representation of parking including header
    * @param random random generator used for sampling parking locations
    * @param includesHeader true if the parkingDescription includes a csv-style header
    * @return
    */
  def apply(
    parkingDescription: Iterator[String],
    tazTreeMap: TAZTreeMap,
    geo: GeoUtils,
    random: Random,
    probabilityOfResidentialCharging: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    includesHeader: Boolean = true
  ): ZonalParkingManager = {
    val parking = ParkingZoneFileUtils.fromIterator(parkingDescription, header = includesHeader)
    new ZonalParkingManager(
      tazTreeMap,
      geo,
      parking.zones,
      parking.tree,
      random,
      maxSearchRadius,
      probabilityOfResidentialCharging,
      boundingBox
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
}
