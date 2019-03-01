package beam.agentsim.infrastructure

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.charging.ChargingInquiryData
import beam.agentsim.infrastructure.parking.ParkingRanking.RankingAccumulator
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree


class ZonalParkingManager(
  val beamServices: BeamServices,
  parkingZones: Array[ParkingZone],
  zoneSearchTree: ParkingZoneSearch.ZoneSearch,
  rand: Random
) extends Actor
    with HasServices
    with ActorLogging {

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = parkingZones.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  override def receive: Receive = {

    case ReleaseParkingStall(parkingZoneId) =>
      if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
        if (log.isDebugEnabled) {
          // this is an infinitely available resource; no update required
          log.debug("Releasing a parking stall for the default parking zone")
        }
      } else if (parkingZoneId < 0 || parkingZones.length <= parkingZoneId) {
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

    case inquiry: DepotParkingInquiry =>
      // we are receiving this because the Ride Hail Manager is seeking alternatives to its managed parking zones

      val inquiryReserveStall
        : Boolean = true // this comes on standard inquiries, and maybe Depot inquiries in the future

      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry with {} available stalls ", totalStallsAvailable)
      }

      // looking for publicly-available parking options
      val (parkingZone, parkingStall) = ZonalParkingManager.incrementalParkingZoneSearch(
        500.0,
        ZonalParkingManager.MaxSearchRadius,
        inquiry.customerLocationUtm,
        ZonalParkingManager.ParkingDurationForRideHailAgents,
        Seq(ParkingType.Public),
        None,
        zoneSearchTree,
        parkingZones,
        beamServices.tazTreeMap.tazQuadTree,
        rand
      )

      // reserveStall is false when agent is only seeking pricing information
      if (inquiryReserveStall) {
        // update the parking stall data
        val claimed: Boolean = ParkingZone.claimStall(parkingZone).value
        if (claimed) {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
        }

        if (log.isDebugEnabled) {
          log.debug("DepotParkingInquiry {} available stalls ", totalStallsAvailable)
        }
      }

      // send found stall
      val response = DepotParkingInquiryResponse(Some(parkingStall), inquiry.requestId)
      sender() ! response

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      val preferredParkingTypes: Seq[ParkingType] = inquiry.activityType match {
        case act if act.equalsIgnoreCase("home") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("work") => Seq(ParkingType.Workplace, ParkingType.Public)
        case _                                   => Seq(ParkingType.Public)
      }

      // performs a concentric ring search from the present location to find a parking stall, and creates it
      val (parkingZone, parkingStall) = ZonalParkingManager.incrementalParkingZoneSearch(
        500.0,
        ZonalParkingManager.MaxSearchRadius,
        inquiry.customerLocationUtm,
        inquiry.parkingDuration.toInt,
        preferredParkingTypes,
        inquiry.chargingInquiryData,
        zoneSearchTree,
        parkingZones,
        beamServices.tazTreeMap.tazQuadTree,
        rand
      )

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
  }
}

object ZonalParkingManager {

  val ParkingDurationForRideHailAgents: Int = 30 * 60 // 30 minutes?
  val SearchFactor: Double = 2.0 // increases search radius by this factor at each iteration
  val MaxSearchRadius: Double = 10e3
  val DefaultParkingPrice: Double = 0.0
  val ParkingAvailabilityThreshold: Double = 0.25

  /**
    * constructs a ZonalParkingManager
    * @param beamServices central repository for simulation data
    * @param random random number generator used to sample parking stall locations
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    beamServices: BeamServices,
    random: Random
  ): ZonalParkingManager = {
    val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parking
    val (stalls, searchTree) = ParkingZoneFileUtils.fromFile(pathResourceCSV)

    // add something like this to write to the current instance directory?
    //  val _ = ParkingZoneFileUtils.toFile(searchTree, stalls, someDestinationForThisConfiguration)

    new ZonalParkingManager(beamServices, stalls, searchTree, random)
  }

  /**
    * builds a ZonalParkingManager Actor
    * @param beamServices core services related to this simulation
    * @param beamRouter Actor responsible for routing decisions (deprecated/previously unused)
    * @param parkingStockAttributes intended to set parking defaults (deprecated/previously unused)
    * @return
    */
  def props(
    beamServices: BeamServices,
    beamRouter: ActorRef,
    parkingStockAttributes: ParkingStockAttributes,
    random: Random = new Random(System.currentTimeMillis)
  ): Props = {
    Props(ZonalParkingManager(beamServices, random))
  }

  /**
    * looks for the nearest ParkingZone that meets the agent's needs
    * @param searchStartRadius small radius describing a ring shape
    * @param searchMaxRadius larger radius describing a ring shape
    * @param destination coordinates of this request
    * @param parkingDuration duration requested for this parking, used to calculate cost in ranking
    * @param parkingTypes types of parking this request is interested in
    * @param chargingInquiryData optional inquiry preferences for charging options
    * @param searchTree nested map structure assisting search for parking within a TAZ and by parking type
    * @param stalls collection of all parking alternatives
    * @param tazQuadTree lookup of all TAZs in this simulation
    * @param random random generator used to sample a location from the TAZ for this stall
    * @return a stall from the found ParkingZone, or a ParkingStall.DefaultStall
    */
  def incrementalParkingZoneSearch(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    destination: Location,
    parkingDuration: Int,
    parkingTypes: Seq[ParkingType],
    chargingInquiryData: Option[ChargingInquiryData],
    searchTree: ParkingZoneSearch.ZoneSearch,
    stalls: Array[ParkingZone],
    tazQuadTree: QuadTree[TAZ],
    random: Random
  ): (ParkingZone, ParkingStall) = {

    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > searchMaxRadius) None
      else {
        val tazDistance: Map[TAZ, Double] =
          tazQuadTree.
            getRing(destination.getX, destination.getY, thisInnerRadius, thisOuterRadius).
            asScala.
            map { taz =>
              (taz, GeoUtils.distFormula(taz.coord, destination))
            }.
            toMap
        val tazList: List[TAZ] = tazDistance.keys.toList

        ParkingZoneSearch.find(
          chargingInquiryData,
          tazList,
          parkingTypes,
          searchTree,
          stalls,
          ParkingRanking.rankingFunction(parkingDuration = parkingDuration)
        ) match {
          case Some(RankingAccumulator(bestTAZ, bestParkingType, bestParkingZone, bestRankingValue, availability)) =>

            val stallPrice: Double =
              bestParkingZone.
                pricingModel.
                  map{ PricingModel.evaluateParkingTicket(_, parkingDuration)}.
                  getOrElse(DefaultParkingPrice)

            val bestTAZCharacteristicDiameter: Double = math.sqrt(bestTAZ.areaInSquareMeters)
            val distanceToBestTAZ: Double = tazDistance(bestTAZ)
            val availabilityPercentage: Double =
              ParkingRanking.getAvailabilityPercentage(
                availability,
                bestParkingZone.pricingModel,
                bestParkingZone.chargingPoint,
                bestParkingType
              )
            // if there is lower availability of parking, we must walk further
            val sampleRadius: Double = (1 - availabilityPercentage) * distanceToBestTAZ

            val stallLocation: Coord = if (distanceToBestTAZ < bestTAZCharacteristicDiameter) {
              // stall coordinate should be sampled in relation to driver agent destination
              sampleLocationForStall(random, destination, sampleRadius)
            } else {
              // stall coordinate should be sampled in relation to TAZ center
              sampleLocationForStall(random, bestTAZ.coord, sampleRadius)
            }

            // create a new stall instance. you win!
            val newStall = ParkingStall(
              bestTAZ.tazId,
              bestParkingZone.parkingZoneId,
              stallLocation,
              stallPrice,
              bestParkingZone.chargingPoint,
              bestParkingZone.pricingModel,
              bestParkingType
            )

            Some { (bestParkingZone, newStall) }
          case None =>
            //
            _search(thisOuterRadius, thisOuterRadius * SearchFactor)
        }
      }
    }

    _search(0, searchStartRadius) match {
      case Some(result) =>
        result
      case None =>
        val newStall = ParkingStall.DefaultStall(destination)
        (ParkingZone.DefaultParkingZone, newStall)
    }
  }

  /**
    * samples a random location near a TAZ's centroid in order to create a stall in that TAZ.
    * previous dev's note: make these distributions more custom to the TAZ and stall type
    * @param rand random generator
    * @param center location we are sampling from
    *
    * @return a coordinate near that TAZ
    */
  def sampleLocationForStall(rand: Random, center: Location, radius: Double): Location = {
    val lambda = 0.01
    val deltaRadiusX = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda
    val deltaRadiusY = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = center.getX + deltaRadiusX
    val y = center.getY + deltaRadiusY
    new Location(x, y)
  }
}
