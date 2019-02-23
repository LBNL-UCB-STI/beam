package beam.agentsim.infrastructure

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.charging.ChargingInquiryData
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.{BeamServices, HasServices}
import org.matsim.core.utils.collections.QuadTree

class ZonalParkingManager(
  val beamServices: BeamServices,
  stalls: Array[ParkingZone],
  searchTree: ParkingZoneSearch.StallSearch,
  rand: Random
) extends Actor
    with HasServices
    with ActorLogging {

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = stalls.map { _.stallsAvailable }.foldLeft(0L) { _ + _ }

  override def receive: Receive = {

    case ReleaseParkingStall(parkingZoneId) =>
      if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
        if (log.isDebugEnabled) {
          // this is an infinitely available resource; no update required
          log.debug("Releasing a parking stall for the default parking zone")
        }
      } else if (parkingZoneId < 0 || stalls.length <= parkingZoneId) {
        if (log.isDebugEnabled) {
          log.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
        }
      } else {
        ParkingZone.releaseStall(stalls(parkingZoneId))
        totalStallsInUse -= 1
        totalStallsAvailable += 1
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
        searchTree,
        stalls,
        beamServices.tazTreeMap.tazQuadTree,
        rand
      )

      // reserveStall is false when agent is only seeking pricing information
      if (inquiryReserveStall) {
        // update the parking stall data
        ParkingZone.claimStall(parkingZone)
        totalStallsInUse += 1
        totalStallsAvailable -= 1

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
        searchTree,
        stalls,
        beamServices.tazTreeMap.tazQuadTree,
        rand
      )

      // reserveStall is false when agent is only seeking pricing information
      if (inquiry.reserveStall) {
        // update the parking stall data
        ParkingZone.claimStall(parkingZone)
        totalStallsInUse += 1
        totalStallsAvailable -= 1

        log.debug(s"Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)

        if (totalStallsInUse % 1000 == 0) log.debug(s"Parking stalls in use: {}", totalStallsInUse)
      }

      sender() ! ParkingInquiryResponse(parkingStall, inquiry.requestId)
  }
}

object ZonalParkingManager {

  val ParkingDurationForRideHailAgents: Int = 30 * 60 // 30 minutes?
  val SearchFactor: Double = 2.0 // increases search radius by this factor at each iteration
  val MaxSearchRadius = 10e3

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
    * @param location coordinates of this request
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
    location: Location,
    parkingDuration: Int,
    parkingTypes: Seq[ParkingType],
    chargingInquiryData: Option[ChargingInquiryData],
    searchTree: ParkingZoneSearch.StallSearch,
    stalls: Array[ParkingZone],
    tazQuadTree: QuadTree[TAZ],
    random: Random
  ): (ParkingZone, ParkingStall) = {

    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > searchMaxRadius) None
      else {
        val tazList: Seq[TAZ] =
          tazQuadTree
            .getRing(location.getX, location.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .toList

        ParkingZoneSearch.find(
          chargingInquiryData,
          tazList,
          parkingTypes,
          searchTree,
          stalls,
          ParkingRankingFunction(parkingDuration = parkingDuration)
        ) match {
          case Some((bestTAZ, bestType, bestParkingZone, bestParkingZoneId)) =>
            // create a stall from this parking zone
            val location = sampleLocationForStall(random, bestTAZ)
            val newStall = ParkingStall(
              bestTAZ.tazId,
              bestParkingZoneId,
              location,
              0.0D,
              bestParkingZone.chargingPoint,
              bestParkingZone.pricingModel,
              bestType
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
        val newStall = ParkingStall.DefaultStall(location)
        (ParkingZone.DefaultParkingZone, newStall)
    }
  }

  /**
    * samples a random location near a TAZ's centroid in order to create a stall in that TAZ.
    * previous dev's note: make these distributions more custom to the TAZ and stall type
    * @param rand random generator
    * @param taz TAZ we are sampling from
    * @return a coordinate near that TAZ
    */
  def sampleLocationForStall(rand: Random, taz: TAZ): Location = {
    val radius = math.sqrt(taz.areaInSquareMeters) / 2
    val lambda = 0.01
    val deltaRadiusX = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda
    val deltaRadiusY = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = taz.coord.getX + deltaRadiusX
    val y = taz.coord.getY + deltaRadiusY
    new Location(x, y)
  }
}
