package beam.agentsim.infrastructure

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

class ZonalParkingManager[GEO: GeoLevel](
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
) extends ParkingNetwork[GEO] {

  if (maxSearchRadius < minSearchRadius) {
    logger.warn(
      s"maxSearchRadius of $maxSearchRadius meters provided from config is less than the fixed minimum search radius of $minSearchRadius; no searches will occur with these settings."
    )
  }

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _._2.stallsAvailable }.foldLeft(0L) { _ + _ }

  val functions: ParkingAndChargingFunctions[GEO] = new ParkingAndChargingFunctions(
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

    Some(ParkingInquiryResponse(parkingStall, inquiry.requestId))
  }

  override def processReleaseParkingStall(release: ReleaseParkingStall) = {
    val parkingZoneId = release.stall.parkingZoneId
    if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
      // this is an infinitely available resource; no update required
      logger.debug("Releasing a stall in the default/emergency zone")
    } else if (!parkingZones.contains(parkingZoneId)) {
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
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    searchTree: ZoneSearchTree[GEO],
    geo: GeoUtils,
    random: Random,
    boundingBox: Envelope
  ): ZonalParkingManager[GEO] = {
    new ZonalParkingManager(
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      geo,
      parkingZones,
      searchTree,
      random,
      beamConfig.beam.agentsim.agents.parking.minSearchRadius,
      beamConfig.beam.agentsim.agents.parking.maxSearchRadius,
      boundingBox,
      mnlMultiplierParametersFromConfig(beamConfig),
      beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint
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
      parking.zones.toMap,
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
    * builds a ZonalParkingManager Actor with provided parkingZones and geoQuadTree
    *
    * @return
    */
  def init[GEO: GeoLevel](
    beamConfig: BeamConfig,
    geoQuadTree: QuadTree[GEO],
    idToGeoMapping: scala.collection.Map[Id[GEO], GEO],
    geoToTAZ: GEO => TAZ,
    geo: GeoUtils,
    boundingBox: Envelope,
    parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    searchTree: ZoneSearchTree[GEO],
    seed: Int
  ): ParkingNetwork[GEO] = {
    ZonalParkingManager(
      beamConfig,
      geoQuadTree,
      idToGeoMapping,
      geoToTAZ,
      parkingZones,
      searchTree,
      geo,
      new Random(seed),
      boundingBox
    )
  }
}
