package beam.agentsim.infrastructure

import scala.collection.JavaConverters._
import scala.util.Random
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.parking.charging.ChargingInquiryData
import beam.agentsim.infrastructure.parking.{ParkingRankingFunction, ParkingType, ParkingZone}
import org.matsim.core.utils.collections.QuadTree
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.Id

import scala.annotation.tailrec


class ZonalParkingManager(
  override val beamServices: BeamServices,
  val beamRouter: ActorRef,
  rand: Random = new Random()
) extends Actor
    with HasServices
    with ActorLogging {

  val CostOfEmergencyStall: Double = 1000.0           // used as an emergency when no stalls were found
  val SearchStartRadius: Double = 500.0               // original public search defaults; depot's was 10000.0
  val SearchMaxRadius: Double = 16000.0               // depot's was 20000.0
  //  var stallNum = 0 // this was the counter to issue new stall ids

  val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parking
  val (stalls, searchTree) = ParkingZoneSearch.io.fromFile(pathResourceCSV)

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = stalls.map{_.stallsAvailable}.foldLeft(0L){_+_}

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

    // we are receiving this because the Ride Hail Manager is seeking alternatives to its managed parking zones
    case inquiry: DepotParkingInquiry =>

      val inquiryReserveStall: Boolean = true // this comes on standard inquiries, and maybe Depot inquiries in the future

      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry with {} available stalls ", totalStallsAvailable)
      }

      // looking for publicly-available parking options
      val (parkingZone, parkingStall) = ZonalParkingManager.incrementalParkingZoneSearch(
        10000,
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

      log.debug("Received parking inquiry: {}", inquiry);

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
  val SearchFactor: Double = 2.0                      // increases search radius by this factor at each iteration

  /**
    * builds a ZonalParkingManager Actor
    * @param beamServices
    * @param beamRouter
    * @param parkingStockAttributes unused; remove this
    * @return
    */
  def props(
             beamServices: BeamServices,
             beamRouter: ActorRef,
             parkingStockAttributes: ParkingStockAttributes
           ): Props = {
    Props(new ZonalParkingManager(beamServices, beamRouter))
  }

  val MaxSearchRadius = 10e3 // was listed but unused in original code.. using it?


  /**
    * looks for the nearest ParkingZone that meets the agent's needs
    * @param searchStartRadius
    * @param searchMaxRadius
    * @param location
    * @param parkingDuration
    * @param parkingTypes
    * @param chargingInquiryData
    * @param searchTree
    * @param stalls
    * @param tazQuadTree
    * @param random
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
            Some{ (bestParkingZone, newStall) }
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
