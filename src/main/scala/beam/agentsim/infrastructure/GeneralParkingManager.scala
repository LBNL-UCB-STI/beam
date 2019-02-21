package beam.agentsim.infrastructure

import scala.collection.JavaConverters._
import scala.util.Random

import akka.actor.{Actor, ActorLogging, ActorRef}
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.parking.charging.ChargingInquiryData
import beam.agentsim.infrastructure.parking.{ParkingCostFunction, ParkingType, ParkingZone}
import org.matsim.core.utils.collections.QuadTree
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.infrastructure.parking.ParkingZoneSearch
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, HasServices}


class GeneralParkingManager(
  override val beamServices: BeamServices,
  val beamRouter: ActorRef,
  rand: Random = new Random()
) extends Actor
    with HasServices
    with ActorLogging {

  val CostOfEmergencyStall: Double = 1000.0           // used as an emergency when no stalls were found
  val SearchStartRadius: Double = 500.0               // original public search defaults; depot's was 10000.0
  val SearchMaxRadius: Double = 16000.0               // depot's was 20000.0
  val SearchFactor: Double = 2.0                      // increases search radius by this factor at each iteration
  //  var stallNum = 0 // this was the counter to issue new stall ids

  val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parking
  val (stalls, searchTree) = ParkingZoneSearch.io.fromFile(pathResourceCSV)

  var totalStallsInUse: Long = 0L
  var totalStallsAvailable: Long = stalls.map{_.stallsAvailable}.fold(0L){_+_}

  override def receive: Receive = {

    // we are receiving this because the Ride Hail Manager is seeking alternatives to its managed parking zones
    case inquiry: DepotParkingInquiry =>
      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry with {} available stalls ", totalStallsAvailable)
      }

      // performs a concentric search from the present location to find TAZs up to the SearchMaxRadius
      val tazList: Vector[(TAZ, Double)] =
        GeneralParkingManager.findTAZsWithinDistance(
          beamServices.tazTreeMap.tazQuadTree,
          inquiry.customerLocationUtm,
          SearchStartRadius,
          SearchMaxRadius
        )

      // get parking zone indices and their ranking
      val (parkingZone, parkingStall) =
        GeneralParkingManager.findAndCreateStall(
          inquiry.customerLocationUtm,
          tazList,
          parkingTypes = Seq(ParkingType.Public),
          chargingInquiryData = None,
          searchTree,
          stalls,
          rand
        )


      // update the parking stall data
      ParkingZone.claimStall(parkingZone)
      totalStallsInUse += 1
      totalStallsAvailable -= 1

      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry {} available stalls ", totalStallsAvailable)
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

      // performs a concentric search from the present location to find TAZs up to the SearchMaxRadius
      val tazList: Vector[(TAZ, Double)] =
        GeneralParkingManager.findTAZsWithinDistance(
          beamServices.tazTreeMap.tazQuadTree,
          inquiry.customerLocationUtm,
          SearchStartRadius,
          SearchMaxRadius
        )

      // get parking zone indices and their ranking
      val (parkingZone, parkingStall) =
        GeneralParkingManager.findAndCreateStall(
          inquiry.customerLocationUtm,
          tazList,
          parkingTypes = preferredParkingTypes,
          chargingInquiryData = inquiry.chargingInquiryData,
          searchTree,
          stalls,
          rand
        )

      // update the parking stall data
      ParkingZone.claimStall(parkingZone)
      totalStallsInUse += 1
      totalStallsAvailable -= 1

      if (totalStallsInUse % 1000 == 0) log.debug(s"Parking stalls in use: {}", totalStallsInUse)

      sender() ! ParkingInquiryResponse(parkingStall, inquiry.requestId)
  }
}


object GeneralParkingManager {

  val ParkingDurationForRideHailAgents: Int = 30 * 60 // 30 minutes?

  /**
    * take a stall from the infinite parking zone
    * @param location location of this inquiry
    * @return a stall that costs a lot but at least it exists
    */
  def DefaultStall(location: Location) =
    ParkingStall(
      locationUTM = location,
      cost = 1000D,
      chargingPoint = None,
      pricingModel = None
    )

  val DefaultParkingZone = ParkingZone(Int.MaxValue, None, None)


  /**
    * performs a concentric search from the present location to find TAZs up to the SearchMaxRadius
    * @param searchCenter
    * @param startRadius
    * @param maxRadius
    * @return
    */
  def findTAZsWithinDistance(tazQuadTree: QuadTree[TAZ], searchCenter: Location, startRadius: Double, maxRadius: Double): Vector[(TAZ, Double)] = {
    var nearbyTAZs: Vector[TAZ] = Vector()
    var searchRadius = startRadius
    while (nearbyTAZs.isEmpty && searchRadius <= maxRadius) {
      nearbyTAZs = tazQuadTree
        .getDisk(searchCenter.getX, searchCenter.getY, searchRadius)
        .asScala
        .toVector
      searchRadius = searchRadius * 2.0
    }
    nearbyTAZs
      .zip(nearbyTAZs.map { taz =>
        // Note, this assumes both TAZs and SearchCenter are in local coordinates, and therefore in units of meters
        GeoUtils.distFormula(taz.coord, searchCenter)
      })
      .sortBy(_._2)
  }


  def findAndCreateStall(
    location: Location,
    tazList: Vector[(TAZ, Double)],
    parkingTypes: Seq[ParkingType],
    chargingInquiryData: Option[ChargingInquiryData],
    searchTree: ParkingZoneSearch.StallSearch,
    stalls: Array[ParkingZone],
    random: Random
  ): (ParkingZone, ParkingStall) = {

    // get parking zone indices and their ranking
    ParkingZoneSearch.find(
      None,
      tazList.map{_._1},
      List(ParkingType.Public),
      searchTree,
      stalls,
      ParkingCostFunction(parkingDuration = ParkingDurationForRideHailAgents)
    ) match {
      case Some((bestTAZ, bestParkingZone, _)) =>
        // create a stall from this parking zone
        val location = sampleLocationForStall(random, bestTAZ)
        val newStall = ParkingStall(
          location,
          0.0D,
          bestParkingZone.chargingPoint,
          bestParkingZone.pricingModel
        )
        (bestParkingZone, newStall)
      case None =>
        // take a stall from the nearest infinite parking zone
        val newStall = GeneralParkingManager.DefaultStall(location)
        (GeneralParkingManager.DefaultParkingZone, newStall)
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
