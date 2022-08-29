package beam.agentsim.infrastructure

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.HierarchicalParkingManager._
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone.UbiqiutousParkingAvailability
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.HasCoord
import beam.utils.metrics.SimpleCounter
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.language.existentials
import scala.util.Random

/**
  * This class accepts link mapping and makes TAZ level mapping out of the link mapping.
  * During handling a parking inquiry it search TAZ level first. Then it takes a similar parking zone from the links
  * that belong to the TAZ where appropriate parking stall is found.
  * Links are searched from starting point (the nearest link to inquiry location) withing TAZ.
  * @author Dmitry Openkov
  */
class HierarchicalParkingManager(
  parkingZones: Map[Id[ParkingZoneId], ParkingZone],
  tazMap: TAZTreeMap,
  distanceFunction: (Coord, Coord) => Double,
  minSearchRadius: Double,
  maxSearchRadius: Double,
  boundingBox: Envelope,
  seed: Int,
  mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
  checkThatNumberOfStallsMatch: Boolean = false
) extends ParkingNetwork(parkingZones) {

  protected val (tazParkingZones, linkZoneToTazZoneMap) =
    convertToTazParkingZones(
      parkingZones
    )

  override protected val searchFunctions: Option[InfrastructureFunctions] = Some(
    new ParkingFunctions(
      tazMap.tazQuadTree,
      tazMap.idToTAZMapping,
      tazParkingZones,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      0.0,
      0.0,
      1.0,
      1,
      boundingBox,
      seed,
      mnlParkingConfig
    )
  )

  val DefaultParkingZone: ParkingZone =
    ParkingZone.defaultInit(
      TAZ.DefaultTAZId,
      ParkingType.Public,
      UbiqiutousParkingAvailability
    )

  /**
    * For each TAZ it contains a Map: ParkingZoneDescription -> ParkingZoneTreeMap
    */
  protected val tazSearchMap: Map[Id[TAZ], Map[ParkingZoneDescription, QuadTree[ParkingZone]]] =
    createDescriptionToZonesMapForEachTaz(parkingZones, tazMap.idToTAZMapping)

  if (checkThatNumberOfStallsMatch) {
    val wrongTaz = wrongNumStallTazCollection()
    if (wrongTaz.nonEmpty) {
      logger.warn(s"Number of non matched stalls on TAZ and link level: ${wrongTaz.size}")
    }
  }

  /**
    * @param inquiry ParkingInquiry
    * @param parallelizationCounterOption Option[SimpleCounter]
    *  @return
    */
  override def processParkingInquiry(
    inquiry: ParkingInquiry,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): Option[ParkingInquiryResponse] = {
    logger.debug("Received parking inquiry: {}", inquiry)

    //searchForParkingStall always returns a ParkingZoneSearchResult. It may contain either a real parkingStall
    // (success) or emergency parking stall (not found an appropriate one)
    val Some(ParkingZoneSearch.ParkingZoneSearchResult(tazParkingStall, tazParkingZone, _, _, _)) =
      searchFunctions.get.searchForParkingStall(inquiry)

    val (parkingStall: ParkingStall, parkingZone: ParkingZone) =
      if (TAZ.isSpecialTazId(tazParkingStall.tazId)) tazParkingStall -> DefaultParkingZone
      else {
        val descriptionToZone = tazSearchMap(tazParkingZone.tazId)
        findAppropriateLinkParkingZoneWithinTaz(tazParkingZone, descriptionToZone, inquiry.destinationUtm.loc) match {
          case Some(zone) =>
            val linkParkingStall = tazParkingStall.copy(
              parkingZoneId = zone.parkingZoneId,
              locationUTM = zone.link.fold(tazParkingStall.locationUTM)(_.getCoord)
            )
            linkParkingStall -> zone
          case None =>
            logger.error(
              "Cannot find link parking parking zone for taz zone {}. Parallel changing of stallsAvailable?",
              tazParkingZone
            )
            lastResortStallAndZone(inquiry.destinationUtm.loc)
        }
      }

    // reserveStall is false when agent is only seeking pricing information
    if (inquiry.reserveStall) {

      logger.debug(
        s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging"
        else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
      )

      ParkingZone.claimStall(parkingZone)
      searchFunctions.get.claimStall(tazParkingZone)
    }

    Some(ParkingInquiryResponse(parkingStall, inquiry.requestId, inquiry.triggerId))
  }

  def findStartingPoint(taz: TAZ, destination: Coord): Coord = {
    if (GeoUtils.isPointWithinCircle(taz.coord, taz.areaInSquareMeters / Math.PI, destination))
      destination
    else GeoUtils.segmentCircleIntersection(taz.coord, Math.sqrt(taz.areaInSquareMeters / Math.PI), destination)
  }

  private def findAppropriateLinkParkingZoneWithinTaz(
    tazParkingZone: ParkingZone,
    descriptionToZone: Map[ParkingZoneDescription, QuadTree[ParkingZone]],
    destination: Coord
  ): Option[ParkingZone] = {
    val foundZoneDescription = ParkingZoneDescription.describeParkingZone(tazParkingZone)
    val treeMap: QuadTree[ParkingZone] = descriptionToZone(foundZoneDescription)
    val taz = tazMap.idToTAZMapping(tazParkingZone.tazId)

    val startingPoint: Coord = findStartingPoint(taz, destination)
    TAZTreeMap.ringSearch(
      treeMap,
      startingPoint,
      100,
      1000000,
      radiusMultiplication = 1.5
    ) { parkingZone =>
      if (parkingZone.stallsAvailable > 0) Some(parkingZone)
      else None
    }
  }

  /**
    * @param release ReleaseParkingStall
    *  @return
    */
  override def processReleaseParkingStall(release: ReleaseParkingStall): Boolean = {
    val parkingZoneId = release.stall.parkingZoneId
    if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
      // this is an infinitely available resource; no update required
      logger.debug("Releasing a stall in the default/emergency zone")
      true
    } else if (!parkingZones.contains(parkingZoneId)) {
      logger.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
      false
    } else {
      val linkZone = parkingZones(parkingZoneId)
      val tazZoneId = linkZoneToTazZoneMap(parkingZoneId)
      val tazZone = tazParkingZones(tazZoneId)
      ParkingZone.releaseStall(linkZone)
      searchFunctions.get.releaseStall(tazZone)
    }
  }

  /**
    * This method can be used for validating that stallsAvailable is the same for each TAZ and sum of all the links that
    * belongs to this TAZ
    */
  private def wrongNumStallTazCollection(): Iterable[Id[TAZ]] = {
    tazMap.getTAZs
      .flatMap { taz =>
        val linkStalls = (for {
          descriptionsToQuadTrees <- tazSearchMap.get(taz.tazId)
          totalStalls = descriptionsToQuadTrees.values
            .map(_.values().stream().mapToLong(_.stallsAvailable).sum)
            .sum
        } yield totalStalls).getOrElse(0L)

        val tazStalls = (for {
          zoneCollection <- searchFunctions.get.zoneCollections.get(taz.tazId)
          totalStalls = zoneCollection.parkingZones.map(_.stallsAvailable.toLong).sum
        } yield totalStalls).getOrElse(0)
        if (tazStalls != linkStalls) Some(taz.tazId) else None
      }
  }

  private def lastResortStallAndZone(location: Location) = {
    val boxAroundRequest = new Envelope(
      location.getX + 2000,
      location.getX - 2000,
      location.getY + 2000,
      location.getY - 2000
    )
    val newStall = ParkingStall.lastResortStall(boxAroundRequest, new Random(seed))
    newStall -> DefaultParkingZone
  }
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
    reservedFor: ReservedFor,
    chargingPointType: Option[ChargingPointType],
    pricingModel: Option[PricingModel],
    timeRestrictions: Map[VehicleCategory, Range]
  )

  object ParkingZoneDescription {

    def describeParkingZone(zone: ParkingZone): ParkingZoneDescription = {
      new ParkingZoneDescription(
        zone.parkingType,
        zone.reservedFor,
        zone.chargingPointType,
        zone.pricingModel,
        zone.timeRestrictions
      )
    }
  }

  def apply(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    tazMap: TAZTreeMap,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
    checkThatNumberOfStallsMatch: Boolean = false
  ): ParkingNetwork = {
    new HierarchicalParkingManager(
      parkingZones,
      tazMap,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      seed,
      mnlParkingConfig,
      checkThatNumberOfStallsMatch
    )
  }

  def init(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    tazMap: TAZTreeMap,
    distanceFunction: (Coord, Coord) => Double,
    minSearchRadius: Double,
    maxSearchRadius: Double,
    boundingBox: Envelope,
    seed: Int,
    mnlParkingConfig: BeamConfig.Beam.Agentsim.Agents.Parking.MulitnomialLogit,
    checkThatNumberOfStallsMatch: Boolean = false
  ): ParkingNetwork =
    HierarchicalParkingManager(
      parkingZones,
      tazMap,
      distanceFunction,
      minSearchRadius,
      maxSearchRadius,
      boundingBox,
      seed,
      mnlParkingConfig,
      checkThatNumberOfStallsMatch
    )

  /**
    * Makes TAZ level parking data from the link level parking data
    * @param parkingZones link level parking zones
    * @return taz parking zones, link zone id -> taz zone id map
    */
  private[infrastructure] def convertToTazParkingZones(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone]
  ): (Map[Id[ParkingZoneId], ParkingZone], Map[Id[ParkingZoneId], Id[ParkingZoneId]]) = {
    val tazZonesMap = parkingZones.values.groupBy(_.tazId)

    // list of parking zone description including TAZ and link zones
    val tazZoneDescriptions = tazZonesMap.flatMap { case (tazId, currentTazParkingZones) =>
      val tazLevelZoneDescriptions = currentTazParkingZones.groupBy(ParkingZoneDescription.describeParkingZone)
      tazLevelZoneDescriptions.map { case (description, linkZones) =>
        (tazId, description, linkZones)
      }
    }
    //generate taz parking zones
    val tazZones = tazZoneDescriptions.zipWithIndex.map { case ((tazId, description, linkZones), id) =>
      val numStalls = Math.min(linkZones.map(_.maxStalls.toLong).sum, Int.MaxValue).toInt
      val parkingZone = ParkingZone.init(
        Some(Id.create(id, classOf[ParkingZoneId])),
        geoId = tazId,
        parkingType = description.parkingType,
        maxStalls = numStalls,
        reservedFor = description.reservedFor,
        chargingPointType = description.chargingPointType,
        pricingModel = description.pricingModel,
        timeRestrictions = description.timeRestrictions
      )
      parkingZone.parkingZoneId -> parkingZone
    }

    //link zone to taz zone map
    val tazZonesAndLinkZones = tazZones.zip(tazZoneDescriptions.map { case (_, _, linkZones) => linkZones })
    val linkZoneToTazZoneMap = tazZonesAndLinkZones
      .flatMap { case ((tazParkingZoneId, _), linkZones) => linkZones.map(_.parkingZoneId -> tazParkingZoneId) }
    (tazZones.toMap, linkZoneToTazZoneMap.toMap)
  }

  private def createDescriptionToZonesMapForEachTaz(
    parkingZones: Map[Id[ParkingZoneId], ParkingZone],
    idToTazMapping: collection.Map[Id[TAZ], TAZ]
  ): Map[Id[TAZ], Map[ParkingZoneDescription, QuadTree[ParkingZone]]] = {
    val zoneLists =
      parkingZones.values.foldLeft(Map.empty: Map[Id[TAZ], Map[ParkingZoneDescription, IndexedSeq[ParkingZone]]]) {
        case (accumulator, zone) =>
          val zoneDescription = ParkingZoneDescription.describeParkingZone(zone)
          val tazMap = accumulator.getOrElse(zone.tazId, Map.empty)
          val zoneList = tazMap.getOrElse(zoneDescription, IndexedSeq.empty)
          accumulator.updated(zone.tazId, tazMap.updated(zoneDescription, zoneList :+ zone))
      }
    implicit val zoneHasCoord: HasCoord[ParkingZone] =
      (zone: ParkingZone) => zone.link.fold(idToTazMapping(zone.tazId).coord)(_.getCoord)
    zoneLists.mapValues(_.mapValues(zoneList => ShapeUtils.quadTree(zoneList)))
  }

}
