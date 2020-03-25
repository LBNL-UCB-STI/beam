package beam.agentsim.agents.ridehail

import scala.util.{Failure, Random, Success, Try}

import beam.agentsim.agents.choice.logit.MultinomialLogit
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.{
  ParkingAlternative,
  ParkingZoneSearchConfiguration,
  ParkingZoneSearchParams
}
import beam.agentsim.infrastructure.parking.{
  ParkingMNL,
  ParkingType,
  ParkingZone,
  ParkingZoneFileUtils,
  ParkingZoneSearch
}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope

class RideHailDepotParkingManager(
  parkingFilePath: String,
  tazFilePath: String,
  valueOfTime: Double,
  tazTreeMap: TAZTreeMap,
  random: Random,
  boundingBox: Envelope,
  distFunction: (Location, Location) => Double,
  mnlParams: ParkingMNL.ParkingMNLConfig,
  parkingStallCountScalingFactor: Double = 1.0
) extends LazyLogging {

  // load parking from a parking file, or generate it using the TAZ beam input
  val (
    rideHailParkingStalls: Array[ParkingZone],
    rideHailParkingSearchTree: ParkingZoneSearch.ZoneSearchTree[TAZ]
  ) = if (parkingFilePath.isEmpty) {
    logger.info(s"no parking file found. generating ubiquitous ride hail parking")
    ParkingZoneFileUtils
      .generateDefaultParkingFromTazfile(
        tazFilePath,
        random,
        Seq(ParkingType.Workplace)
      )
  } else {
    Try {
      ParkingZoneFileUtils.fromFile(parkingFilePath, random, parkingStallCountScalingFactor)
    } match {
      case Success((stalls, tree)) =>
        logger.info(s"generating ride hail parking from file $parkingFilePath")
        (stalls, tree)
      case Failure(e) =>
        logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
        logger.info(s"generating ubiquitous ride hail parking")
        ParkingZoneFileUtils
          .generateDefaultParkingFromTazfile(
            tazFilePath,
            random,
            Seq(ParkingType.Workplace)
          )
    }
  }

  // track the usage of the RHM agency parking
  var totalStallsInUse: Long = 0
  var totalStallsAvailable: Long = 0

  val parkingZoneSearchConfiguration: ParkingZoneSearchConfiguration =
    ParkingZoneSearchConfiguration(
      RideHailDepotParkingManager.SearchStartRadius,
      RideHailDepotParkingManager.SearchMaxRadius,
      boundingBox,
      distFunction
    )

  /**
    * searches for a nearby [[ParkingZone]] depot for CAV Ride Hail Agents and returns a [[ParkingStall]] in that zone.
    *
    * all parking stalls are expected to be associated with a TAZ stored in the beamScenario.tazTreeMap.
    * the position of the stall will be at the centroid of the TAZ.
    *
    * @param locationUtm the position of this agent
    * @return the id of a ParkingZone, or, nothing if no parking zones are found with availability
    */
  def findDepot(
    locationUtm: Location,
    parkingDuration: Double
  ): Option[ParkingStall] = {

    val parkingZoneSearchParams: ParkingZoneSearchParams =
      ParkingZoneSearchParams(
        locationUtm,
        parkingDuration,
        mnlParams,
        rideHailParkingSearchTree,
        rideHailParkingStalls,
        tazTreeMap.tazQuadTree,
        random
      )

    // current implementation here expects all RHA depot stalls are charging-capable
    // and all inquiries are for the purpose of fast charging
    val parkingZoneFilterFunction: ParkingZone => Boolean = (zone: ParkingZone) => true

    // generates a coordinate for an embodied ParkingStall from a ParkingZone,
    // treating the TAZ centroid as a "depot" location
    val parkingZoneLocSamplingFunction: ParkingZone => Location =
      (zone: ParkingZone) => {
        tazTreeMap.getTAZ(zone.tazId) match {
          case None =>
            logger.error(s"somehow have a ParkingZone with tazId ${zone.tazId} which is not found in the TAZTreeMap")
            TAZ.DefaultTAZ.coord
          case Some(taz) =>
            taz.coord
        }
      }

    // adds multinomial logit parameters to a ParkingAlternative
    val parkingZoneMNLParamsFunction: ParkingAlternative => Map[ParkingMNL.Parameters, Double] =
      (parkingAlternative: ParkingAlternative) => {

        val distance: Double = distFunction(locationUtm, parkingAlternative.coord)

        val averagePersonWalkingSpeed = 1.4 // in m/s
        val hourInSeconds = 3600

        val rangeAnxietyFactor: Double = 0.0 // RHAs are told to charge before this point
        val distanceFactor: Double = (distance / averagePersonWalkingSpeed / hourInSeconds) * valueOfTime
        val parkingCostsPriceFactor: Double = parkingAlternative.costInDollars

        Map(
          ParkingMNL.Parameters.WalkingEgressCost -> distanceFactor,
          ParkingMNL.Parameters.ParkingTicketCost -> parkingCostsPriceFactor,
          ParkingMNL.Parameters.RangeAnxietyCost  -> rangeAnxietyFactor
        )
      }

    for {
      ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, _, parkingZonesSeen, parkingZonesSampled, iterations) <- ParkingZoneSearch
        .incrementalParkingZoneSearch(
          parkingZoneSearchConfiguration,
          parkingZoneSearchParams,
          parkingZoneFilterFunction,
          parkingZoneLocSamplingFunction,
          parkingZoneMNLParamsFunction
        )
      taz <- tazTreeMap.getTAZ(parkingStall.tazId)
    } yield {

      logger.debug(s"found ${parkingZonesSeen.length} parking zones over $iterations iterations")

      // override the sampled stall coordinate with the TAZ centroid -
      // we want all agents who park in this TAZ to park in the same location.
      parkingStall.copy(
        locationUTM = taz.coord
      )
    }
  }

  /**
    * when agent arrives at ParkingZone, this will claim their stall, or will fail with None if no stalls are available.
    *
    *
    *
    * the failure should set off a queueing logic to occur.
    *
    * @param parkingStall the parking stall that the agent was given
    * @return None on failure -> queue this agent and let them know when more parking becomes available
    */
  def findAndClaimStallAtDepot(parkingStall: ParkingStall): Option[ParkingStall] = {
    if (parkingStall.parkingZoneId < 0 || rideHailParkingStalls.length <= parkingStall.parkingZoneId) None
    else {
      val parkingZone: ParkingZone = rideHailParkingStalls(parkingStall.parkingZoneId)
      if (parkingZone.stallsAvailable == 0) {
        None
      } else {
        val success = ParkingZone.claimStall(parkingZone).value
        if (!success) {
          None
        } else {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
          Some {
            parkingStall
          }
        }
      }
    }
  }

  /**
    * releases a single stall in use at this Depot
    *
    * @param parkingStall stall we want to release
    * @return None on failure
    */
  def releaseStall(parkingStall: ParkingStall): Option[Unit] = {
    if (parkingStall.parkingZoneId < 0 || rideHailParkingStalls.length <= parkingStall.parkingZoneId) None
    else {
      val parkingZone: ParkingZone = rideHailParkingStalls(parkingStall.parkingZoneId)
      val success = ParkingZone.releaseStall(parkingZone).value
      if (!success) None
      else
        Some {
          totalStallsInUse -= 1
          totalStallsAvailable += 1
          ()
        }

    }
  }
}

object RideHailDepotParkingManager {
  // a ride hail agent is searching for a charging depot and is not in service of an activity.
  // for this reason, a higher max radius is reasonable.
  val SearchStartRadius: Double = 500.0 // meters
  val SearchMaxRadius: Int = 80465 // 50 miles, in meters

  def apply(
    parkingFilePath: String,
    tazFilePath: String,
    valueOfTime: Double,
    tazTreeMap: TAZTreeMap,
    random: Random,
    boundingBox: Envelope,
    distFunction: (Location, Location) => Double,
    parkingMNLConfig: ParkingMNL.ParkingMNLConfig,
    parkingStallCountScalingFactor: Double
  ): RideHailDepotParkingManager = {
    new RideHailDepotParkingManager(
      parkingFilePath,
      tazFilePath,
      valueOfTime,
      tazTreeMap,
      random,
      boundingBox,
      distFunction,
      parkingMNLConfig,
      parkingStallCountScalingFactor
    )
  }
}
