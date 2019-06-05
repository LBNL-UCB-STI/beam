package beam.agentsim.infrastructure

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.infrastructure.charging._
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

    case inquiry: ParkingInquiry =>
      log.debug("Received parking inquiry: {}", inquiry)

      val preferredParkingTypes: Seq[ParkingType] = inquiry.activityType match {
        case act if act.equalsIgnoreCase("home") => Seq(ParkingType.Residential, ParkingType.Public)
        case act if act.equalsIgnoreCase("work") => Seq(ParkingType.Workplace, ParkingType.Public)
        case _                                   => Seq(ParkingType.Public)
      }

      // performs a concentric ring search from the destination to find a parking stall, and creates it
      val (parkingZone, parkingStall) = ZonalParkingManager.incrementalParkingZoneSearch(
        500.0,
        ZonalParkingManager.MaxSearchRadius,
        inquiry.destinationUtm,
        inquiry.valueOfTime,
        inquiry.parkingDuration,
        preferredParkingTypes,
        inquiry.utilityFunction,
        zoneSearchTree,
        parkingZones,
        beamServices.tazTreeMap.tazQuadTree,
        beamServices.geo.distUTMInMeters,
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

object ZonalParkingManager {

  val ParkingDurationForRideHailAgents: Int = 30 * 60 // 30 minutes?
  val SearchFactor: Double = 2.0 // increases search radius by this factor at each iteration
  val MaxSearchRadius: Double = 10e3
  val DefaultParkingPrice: Double = 0.0
  val ParkingAvailabilityThreshold: Double = 0.25
  val DepotParkingValueOfTime: Double = 0.0 // ride hail drivers do not have a value of time

  /**
    * constructs a ZonalParkingManager from file
    * @param beamServices central repository for simulation data
    * @param random random number generator used to sample parking stall locations
    * @return an instance of the ZonalParkingManager class
    */
  def apply(
    beamServices: BeamServices,
    random: Random
  ): ZonalParkingManager = {
    val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parkingFilePath
    val (stalls, searchTree) = ParkingZoneFileUtils.fromFile(pathResourceCSV)

    // add something like this to write to the current instance directory?
    //  val _ = ParkingZoneFileUtils.toFile(searchTree, stalls, someDestinationForThisConfiguration)

    new ZonalParkingManager(beamServices, stalls, searchTree, random)
  }

  /**
    * constructs a ZonalParkingManager from a string iterator
    * @param parkingDescription line-by-line string representation of parking including header
    * @param beamServices central repository for simulation data
    * @param random random generator used for sampling parking locations
    * @param includesHeader true if the parkingDescription includes a csv-style header
    * @return
    */
  def apply(
    parkingDescription: Iterator[String],
    beamServices: BeamServices,
    random: Random,
    includesHeader: Boolean = true
  ): ZonalParkingManager = {
    val parking = ParkingZoneFileUtils.fromIterator(parkingDescription, includesHeader)
    new ZonalParkingManager(beamServices, parking.zones, parking.tree, random)
  }

  /**
    * builds a ZonalParkingManager Actor
    * @param beamServices core services related to this simulation
    * @param beamRouter Actor responsible for routing decisions (deprecated/previously unused)
    * @return
    */
  def props(
    beamServices: BeamServices,
    beamRouter: ActorRef,
    random: Random = new Random(System.currentTimeMillis)
  ): Props = {
    Props(ZonalParkingManager(beamServices, random))
  }

  /**
    * looks for the nearest ParkingZone that meets the agent's needs
    * @param searchStartRadius small radius describing a ring shape
    * @param searchMaxRadius larger radius describing a ring shape
    * @param destinationUTM coordinates of this request
    * @param parkingDuration duration requested for this parking, used to calculate cost in ranking
    * @param parkingTypes types of parking this request is interested in
    * @param utilityFunction a utility function for parking alternatives
    * @param searchTree nested map structure assisting search for parking within a TAZ and by parking type
    * @param stalls collection of all parking alternatives
    * @param tazQuadTree lookup of all TAZs in this simulation
    * @param random random generator used to sample a location from the TAZ for this stall
    * @return a stall from the found ParkingZone, or a ParkingStall.DefaultStall
    */
  def incrementalParkingZoneSearch(
    searchStartRadius: Double,
    searchMaxRadius: Double,
    destinationUTM: Location,
    valueOfTime: Double,
    parkingDuration: Double,
    parkingTypes: Seq[ParkingType],
    utilityFunction: MultinomialLogit[ParkingZoneSearch.ParkingAlternative, String],
    searchTree: ParkingZoneSearch.ZoneSearch,
    stalls: Array[ParkingZone],
    tazQuadTree: QuadTree[TAZ],
    distanceFunction: (Coord, Coord) => Double,
    random: Random
  ): (ParkingZone, ParkingStall) = {

    @tailrec
    def _search(thisInnerRadius: Double, thisOuterRadius: Double): Option[(ParkingZone, ParkingStall)] = {
      if (thisInnerRadius > searchMaxRadius) None
      else {
        val tazDistance: Map[TAZ, Double] =
          tazQuadTree
            .getRing(destinationUTM.getX, destinationUTM.getY, thisInnerRadius, thisOuterRadius)
            .asScala
            .map { taz =>
              (taz, GeoUtils.distFormula(taz.coord, destinationUTM))
            }
            .toMap
        val tazList: List[TAZ] = tazDistance.keys.toList

        ParkingZoneSearch.find(
          destinationUTM,
          valueOfTime,
          parkingDuration,
          utilityFunction,
          tazList,
          parkingTypes,
          searchTree,
          stalls,
          distanceFunction,
          random
        ) match {
          case Some(
              ParkingZoneSearch.ParkingSearchResult(
                bestTAZ,
                bestParkingType,
                bestParkingZone,
                bestCoord,
                bestRankingValue
              )
              ) =>
            val stallPrice: Double =
              bestParkingZone.pricingModel
                .map { PricingModel.evaluateParkingTicket(_, parkingDuration.toInt) }
                .getOrElse(DefaultParkingPrice)

            // create a new stall instance. you win!
            val newStall = ParkingStall(
              bestTAZ.tazId,
              bestParkingZone.parkingZoneId,
              bestCoord,
              stallPrice,
              bestParkingZone.chargingPointType,
              bestParkingZone.pricingModel,
              bestParkingType
            )

            Some { (bestParkingZone, newStall) }
          case None =>
            _search(thisOuterRadius, thisOuterRadius * SearchFactor)
        }
      }
    }

    _search(0, searchStartRadius) match {
      case Some(result) =>
        result
      case None =>
        val newStall = ParkingStall.lastResortStall(random)
        (ParkingZone.DefaultParkingZone, newStall)
    }
  }
}
