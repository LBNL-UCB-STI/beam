package beam.agentsim.infrastructure

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.infrastructure.HierarchicalParkingManager._
import beam.agentsim.infrastructure.ZonalParkingManager.{loadParkingZones, mnlMultiplierParametersFromConfig}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingMNL.ParkingMNLConfig
import beam.agentsim.infrastructure.parking.ParkingZone.{DefaultParkingZoneId, UbiqiutousParkingAvailability}
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.metrics.SimpleCounter
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.collections.QuadTree

import scala.util.Random

/**
  * This class accepts link mapping and makes TAZ level mapping out of the link mapping.
  * During handling a parking inquiry it search TAZ level first. Then it takes a similar parking zone from the links
  * that belong to the TAZ where appropriate parking stall is found.
  * Links are searched from starting point (the nearest link to inquiry location) withing TAZ.
  * @author Dmitry Openkov
  */
class HierarchicalParkingManager(
  tazMap: TAZTreeMap,
  linkToTAZMapping: Map[Link, TAZ],
  parkingZones: Array[ParkingZone[Link]],
  rand: Random,
  geo: GeoUtils,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  mnlMultiplierParameters: ParkingMNLConfig,
  checkThatNumberOfStallsMatch: Boolean = false,
  chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
) extends ParkingNetwork[Link] {

  private val actualLinkParkingZones: Array[ParkingZone[Link]] = HierarchicalParkingManager.collapse(parkingZones)

  private val tazLinks: Map[Id[TAZ], QuadTree[Link]] = createTazLinkQuadTreeMapping(linkToTAZMapping)
  private val (tazParkingZones, linkZoneToTazZoneMap) =
    convertToTazParkingZones(actualLinkParkingZones, linkToTAZMapping.map {
      case (link, taz) => link.getId -> taz.tazId
    })
  private val tazZoneSearchTree = ParkingZoneFileUtils.createZoneSearchTree(tazParkingZones)

  private val tazSearchFunctions: ZonalParkingManagerFunctions[TAZ] = new ZonalParkingManagerFunctions[TAZ](
    tazMap.tazQuadTree,
    tazMap.idToTAZMapping,
    identity[TAZ],
    geo,
    tazParkingZones,
    tazZoneSearchTree,
    rand,
    minSearchRadius,
    maxSearchRadius,
    boundingBox,
    mnlMultiplierParameters,
    chargingPointConfig
  )

  val DefaultParkingZone: ParkingZone[Link] =
    ParkingZone(
      DefaultParkingZoneId,
      LinkLevelOperations.DefaultLinkId,
      ParkingType.Public,
      UbiqiutousParkingAvailability,
      Seq.empty
    )

  private val linkZoneSearchMap: Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]] =
    createLinkZoneSearchMap(actualLinkParkingZones)

  if (checkThatNumberOfStallsMatch) {
    stallsInfo()
  }

  override def processParkingInquiry(
    inquiry: ParkingInquiry,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): Option[ParkingInquiryResponse] = {
    logger.debug("Received parking inquiry: {}", inquiry)

    val ParkingZoneSearch.ParkingZoneSearchResult(tazParkingStall, tazParkingZone, _, _, _) =
      tazSearchFunctions.searchForParkingStall(inquiry)

    val (parkingStall: ParkingStall, parkingZone: ParkingZone[Link]) = tazLinks.get(tazParkingZone.geoId) match {
      case Some(linkQuadTree) =>
        val foundZoneDescription = ParkingZoneDescription.describeParkingZone(tazParkingZone)
        val startingPoint =
          linkQuadTree.getClosest(inquiry.destinationUtm.loc.getX, inquiry.destinationUtm.loc.getY).getCoord
        TAZTreeMap.ringSearch(
          linkQuadTree,
          startingPoint,
          minSearchRadius / 4,
          maxSearchRadius * 5,
          radiusMultiplication = 1.5
        ) { link =>
          for {
            linkZones <- linkZoneSearchMap.get(link.getId)
            zone      <- linkZones.get(foundZoneDescription) if zone.stallsAvailable > 0
          } yield {
            (tazParkingStall.copy(zone.geoId, parkingZoneId = zone.parkingZoneId, locationUTM = link.getCoord), zone)
          }
        } match {
          case Some(foundResult) => foundResult
          case None => //Cannot find required links within the TAZ, this means the links is too far from the starting point
            logger.warn(
              "Cannot find link parking stall for taz id {}, foundZoneDescription = {}",
              tazParkingZone.geoId,
              foundZoneDescription
            )
            import scala.collection.JavaConverters._
            val tazLinkZones = for {
              link      <- linkQuadTree.values().asScala.toList
              linkZones <- linkZoneSearchMap.get(link.getId)
              zone      <- linkZones.get(foundZoneDescription) if zone.stallsAvailable > 0
            } yield {
              zone
            }
            logger.warn("Actually tazLink zones {}", tazLinkZones)
            lastResortStallAndZone(inquiry.destinationUtm.loc)
        }
      case None => //no corresponding links, this means it's a special zone
        tazParkingStall.geoId match {
          case TAZ.DefaultTAZId =>
            tazParkingStall.copy(geoId = LinkLevelOperations.DefaultLinkId) -> DefaultParkingZone
          case TAZ.EmergencyTAZId =>
            tazParkingStall.copy(geoId = LinkLevelOperations.EmergencyLinkId) -> DefaultParkingZone
          case _ =>
            logger.warn("Cannot find TAZ with id {}", tazParkingZone.geoId)
            lastResortStallAndZone(inquiry.destinationUtm.loc)
        }
    }

    // reserveStall is false when agent is only seeking pricing information
    if (inquiry.reserveStall) {

      logger.debug(
        s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging" else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
      )

      ParkingZone.claimStall(parkingZone)
      ParkingZone.claimStall(tazParkingZone)
    }

    Some(ParkingInquiryResponse(parkingStall, inquiry.requestId, inquiry.triggerId))
  }

  override def processReleaseParkingStall(release: ReleaseParkingStall) = {
    val parkingZoneId = release.stall.parkingZoneId
    if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
      // this is an infinitely available resource; no update required
      logger.debug("Releasing a stall in the default/emergency zone")
    } else if (parkingZoneId < ParkingZone.DefaultParkingZoneId || actualLinkParkingZones.length <= parkingZoneId) {
      logger.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
    } else {
      val linkZone = actualLinkParkingZones(parkingZoneId)
      val tazZoneId = linkZoneToTazZoneMap(parkingZoneId)
      val tazZone = tazParkingZones(tazZoneId)
      ParkingZone.releaseStall(linkZone)
      ParkingZone.releaseStall(tazZone)
    }
  }

  /**
    * This method can be used for validating that stallsAvailable is the same for each TAZ and sum of all the links that
    * belongs to this TAZ
    */
  private def stallsInfo(): Unit = {
    val notMatchedTazIds = tazMap.getTAZs
      .map { taz =>
        val linkStalls = tazLinks
          .get(taz.tazId)
          .map { linkTree =>
            import scala.collection.JavaConverters._
            val links = linkTree.values().asScala
            links.flatMap { link =>
              for {
                map  <- linkZoneSearchMap.get(link.getId).toIterable
                zone <- map.values
              } yield zone.stallsAvailable.toLong
            }.sum
          }
          .getOrElse(0L)
        val tazStalls = (for {
          map     <- tazZoneSearchTree.get(taz.tazId).toIterable
          zoneIds <- map.values
          zoneId  <- zoneIds
        } yield tazParkingZones(zoneId).stallsAvailable.toLong).sum
        if (tazStalls != linkStalls) Some(taz.tazId) else None
      }
    val notMatch = notMatchedTazIds.count(_.nonEmpty)
    if (notMatch == 0) logger.info(s"Number of non matched stalls on TAZ and link level: $notMatch")
    else logger.warn(s"Number of non matched stalls on TAZ and link level: $notMatch")
  }

  private def lastResortStallAndZone(location: Location) = {
    val boxAroundRequest = new Envelope(
      location.getX + 2000,
      location.getX - 2000,
      location.getY + 2000,
      location.getY - 2000
    )
    val newStall = ParkingStall.lastResortStall(
      boxAroundRequest,
      rand,
      tazId = TAZ.EmergencyTAZId,
      geoId = LinkLevelOperations.EmergencyLinkId
    )
    newStall -> DefaultParkingZone
  }

  override def getParkingZones(): Array[ParkingZone[Link]] = parkingZones
}

object HierarchicalParkingManager {

  /**
    * This class "describes" a parking zone (i.e. extended type of parking zone). This allows to search for similar
    * parking zones on other links or TAZes
    * @param parkingType the parking type (Residential, Workplace, Public)
    * @param chargingPointType the charging point type
    * @param pricingModel the pricing model
    */
  case class ParkingZoneDescription(
    parkingType: ParkingType,
    reservedFor: Seq[VehicleCategory],
    chargingPointType: Option[ChargingPointType],
    pricingModel: Option[PricingModel],
    timeRestrictions: Map[VehicleCategory, Range],
    parkingZoneName: Option[String],
    landCostInUSDPerSqft: Option[Double],
    vehicleManager: Option[Id[VehicleManager]] = None,
  )

  object ParkingZoneDescription {

    def describeParkingZone(zone: ParkingZone[_]): ParkingZoneDescription = {
      new ParkingZoneDescription(
        zone.parkingType,
        zone.reservedFor,
        zone.chargingPointType,
        zone.pricingModel,
        zone.timeRestrictions,
        zone.parkingZoneName,
        zone.landCostInUSDPerSqft,
        zone.vehicleManager
      )
    }
  }

  def init(
    tazMap: TAZTreeMap,
    linkToTAZMapping: Map[Link, TAZ],
    parkingZones: Array[ParkingZone[Link]],
    rand: Random,
    geo: GeoUtils,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    mnlMultiplierParameters: ParkingMNLConfig,
    checkThatNumberOfStallsMatch: Boolean = false,
    chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
  ): ParkingNetwork[Link] =
    new HierarchicalParkingManager(
      tazMap,
      linkToTAZMapping,
      parkingZones,
      rand,
      geo,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      mnlMultiplierParameters,
      checkThatNumberOfStallsMatch,
      chargingPointConfig
    )

  def init(
    beamConfig: BeamConfig,
    tazMap: TAZTreeMap,
    linkQuadTree: QuadTree[Link],
    linkToTAZMapping: Map[Link, TAZ],
    geo: GeoUtils,
    boundingBox: Envelope,
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String]
  ): ParkingNetwork[Link] = {
    HierarchicalParkingManager(
      beamConfig,
      tazMap,
      linkQuadTree,
      linkToTAZMapping,
      geo,
      boundingBox,
      parkingFilePath,
      depotFilePaths
    )
  }

  def apply(
    beamConfig: BeamConfig,
    tazMap: TAZTreeMap,
    linkQuadTree: QuadTree[Link],
    linkToTAZMapping: Map[Link, TAZ],
    geo: GeoUtils,
    boundingBox: Envelope,
    parkingFilePath: String,
    depotFilePaths: IndexedSeq[String]
  ): HierarchicalParkingManager = {

    val parkingStallCountScalingFactor = beamConfig.beam.agentsim.taz.parkingStallCountScalingFactor
    val parkingCostScalingFactor = beamConfig.beam.agentsim.taz.parkingCostScalingFactor

    val minSearchRadius = beamConfig.beam.agentsim.agents.parking.minSearchRadius
    val maxSearchRadius = beamConfig.beam.agentsim.agents.parking.maxSearchRadius
    val mnlMultiplierParameters = mnlMultiplierParametersFromConfig(beamConfig)

    val rand = {
      val seed = beamConfig.matsim.modules.global.randomSeed
      new Random(seed)
    }

    val (parkingZones, _) =
      loadParkingZones(
        parkingFilePath,
        depotFilePaths,
        linkQuadTree,
        parkingStallCountScalingFactor,
        parkingCostScalingFactor,
        rand
      )

    new HierarchicalParkingManager(
      tazMap,
      linkToTAZMapping,
      parkingZones,
      rand,
      geo,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      mnlMultiplierParameters,
      chargingPointConfig = beamConfig.beam.agentsim.chargingNetworkManager.chargingPoint
    )
  }

  /**
    * Makes TAZ level parking data from the link level parking data
    * @param parkingZones link level parking zones
    * @param linkToTAZMapping link to TAZ map
    * @return taz parking zones, link zone id -> taz zone id map
    */
  private[infrastructure] def convertToTazParkingZones(
    parkingZones: Array[ParkingZone[Link]],
    linkToTAZMapping: Map[Id[Link], Id[TAZ]]
  ): (Array[ParkingZone[TAZ]], Map[Int, Int]) = {
    val tazZonesMap = parkingZones.groupBy(zone => linkToTAZMapping(zone.geoId))

    // list of parking zone description including TAZ and link zones
    val tazZoneDescriptions = tazZonesMap.flatMap {
      case (tazId, currentTazParkingZones) =>
        val tazLevelZoneDescriptions = currentTazParkingZones.groupBy(ParkingZoneDescription.describeParkingZone)
        tazLevelZoneDescriptions.map {
          case (description, linkZones) =>
            (tazId, description, linkZones)
        }
    }
    //generate taz parking zones
    val tazZones = tazZoneDescriptions.zipWithIndex.map {
      case ((tazId, description, linkZones), id) =>
        val numStalls = Math.min(linkZones.map(_.maxStalls.toLong).sum, Int.MaxValue).toInt
        new ParkingZone[TAZ](
          parkingZoneId = id,
          geoId = tazId,
          parkingType = description.parkingType,
          stallsAvailable = numStalls,
          maxStalls = numStalls,
          reservedFor = description.reservedFor,
          vehicleManager = description.vehicleManager,
          chargingPointType = description.chargingPointType,
          pricingModel = description.pricingModel,
          timeRestrictions = description.timeRestrictions,
          parkingZoneName = description.parkingZoneName,
          landCostInUSDPerSqft = description.landCostInUSDPerSqft,
        )
    }
    //link zone to taz zone map
    val linkZoneToTazZoneMap = tazZones
      .zip(tazZoneDescriptions.map { case (_, _, linkZones) => linkZones })
      .flatMap { case (tazZone, linkZones) => linkZones.map(_.parkingZoneId -> tazZone.parkingZoneId) }
      .toMap
    (tazZones.toArray, linkZoneToTazZoneMap)
  }

  private def createLinkZoneSearchMap(
    parkingZones: Array[ParkingZone[Link]]
  ): Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]] = {
    parkingZones.foldLeft(Map.empty: Map[Id[Link], Map[ParkingZoneDescription, ParkingZone[Link]]]) {
      (accumulator, zone) =>
        val zoneDescription = ParkingZoneDescription.describeParkingZone(zone)
        val parking = accumulator.getOrElse(zone.geoId, Map())
        accumulator.updated(zone.geoId, parking.updated(zoneDescription, zone))
    }
  }

  def createTazLinkQuadTreeMapping(linkToTAZMapping: Map[Link, TAZ]): Map[Id[TAZ], QuadTree[Link]] = {
    val tazToLinks = invertMap(linkToTAZMapping)
    tazToLinks.map {
      case (taz, links) =>
        taz.tazId -> LinkLevelOperations.getLinkTreeMap(links.toSeq)
    }
  }

  private def invertMap(linkToTAZMapping: Map[Link, TAZ]): Map[TAZ, Set[Link]] = {
    linkToTAZMapping.groupBy(_._2).mapValues(_.keys.toSet)
  }

  /**
    * Collapses multiple similar parking zones in the same Link to a single zone
    * @param parkingZones the parking zones
    * @return collapsed parking zones
    */
  def collapse[GEO](parkingZones: Array[ParkingZone[GEO]]): Array[ParkingZone[GEO]] =
    parkingZones
      .groupBy(_.geoId)
      .flatMap {
        case (linkId, zones) =>
          zones
            .groupBy(ParkingZoneDescription.describeParkingZone)
            .map { case (descr, linkZones) => (linkId, descr, linkZones.map(_.maxStalls.toLong).sum) }
      }
      .filter { case (_, _, maxStalls) => maxStalls > 0 }
      .zipWithIndex
      .map {
        case ((linkId, description, maxStalls), id) =>
          val numStalls = Math.min(maxStalls, Int.MaxValue).toInt
          new ParkingZone[GEO](
            parkingZoneId = id,
            geoId = linkId,
            parkingType = description.parkingType,
            stallsAvailable = numStalls,
            maxStalls = numStalls,
            reservedFor = description.reservedFor,
            vehicleManager = description.vehicleManager,
            chargingPointType = description.chargingPointType,
            pricingModel = description.pricingModel,
            timeRestrictions = description.timeRestrictions,
            parkingZoneName = description.parkingZoneName,
            landCostInUSDPerSqft = description.landCostInUSDPerSqft,
          )
      }
      .toArray
}
