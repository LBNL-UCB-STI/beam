package beam.agentsim.agents.ridehail

import beam.agentsim.infrastructure.{ParkingInquiry, ParkingStall}
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, ParkingZoneFileUtils, ParkingZoneSearch}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import com.vividsolutions.jts.geom.Envelope
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.collections.QuadTree

import scala.util.{Failure, Random, Success, Try}

class RideHailDepotParkingManager (
                                    parkingFilePath: String,
                                    tazFilePath: String,
                                    valueOfTime: Double,
                                    tazTreeMap: TAZTreeMap,
                                    random: Random,
                                    boundingBox: Envelope,
                                    distFunction: (Location, Location) => Double
) extends LazyLogging {

  // load parking from a parking file, or generate it using the TAZ beam input
  val (
    rideHailParkingStalls: Array[ParkingZone],
    rideHailParkingSearchTree: ParkingZoneSearch.ZoneSearch[TAZ]
    ) = if (parkingFilePath.isEmpty) {
    ParkingZoneFileUtils
      .generateDefaultParkingFromTazfile(
        tazFilePath,
        Seq(ParkingType.Workplace)
      )
  } else {
    Try {
      ParkingZoneFileUtils.fromFile(parkingFilePath)
    } match {
      case Success((s, t)) => (s, t)
      case Failure(e) =>
        logger.warn(s"unable to read contents of provided parking file $parkingFilePath, got ${e.getMessage}.")
        ParkingZoneFileUtils
          .generateDefaultParkingFromTazfile(
            tazFilePath,
            Seq(ParkingType.Workplace)
          )
    }
  }

  // track the usage of the RHM agency parking
  var totalStallsInUse: Long = 0
  var totalStallsAvailable: Long = 0


  /**
    * searches for a nearby [[ParkingZone]] depot for CAV Ride Hail Agents and returns it's ID (an Int)
    * @param locationUtm the position of this agent
    * @return the id of a ParkingZone, or, nothing if no parking zones are found with availability
    */
  def findDepot(
                 locationUtm: Location
               ): Option[Int] = {

    ParkingZoneSearch.incrementalParkingZoneSearch(
      searchStartRadius = RideHailDepotParkingManager.SearchStartRadius,
      searchMaxRadius = RideHailDepotParkingManager.SearchMaxRadius,
      destinationUTM = locationUtm,
      valueOfTime = valueOfTime,
      parkingDuration = 0.0,
      parkingTypes = Seq(ParkingType.Workplace),
      chargingInquiryData = None,
      rideHailParkingSearchTree,
      rideHailParkingStalls,
      tazTreeMap.tazQuadTree,
      distFunction,
      random,
      boundingBox
    ).map{ case (parkingZone, _) =>
      // we discard the ParkingStall for now, and will instead generate one later when
      // the agent reaches the ParkingZone
      parkingZone.parkingZoneId
    }
  }


  /**
    * when agent arrives at ParkingZone, this will claim their stall, or will fail with None if no stalls are available.
    *
    * the failure should set off a queueing logic to occur.
    *
    * @param parkingZoneId the parking zone where this agent was routed to.
    * @return None on failure -> queue this agent and let them know when more parking becomes available
    */
  def findAndClaimStallAtDepot(parkingZoneId: Int): Option[ParkingStall] = {
    if (parkingZoneId < 0 || rideHailParkingStalls.length <= parkingZoneId) None
    else {
      val parkingZone: ParkingZone = rideHailParkingStalls(parkingZoneId)
      if (parkingZone.stallsAvailable == 0) {
        None
      } else {
        val success = ParkingZone.claimStall(parkingZone).value
        if (!success) {
          None
        } else {
          totalStallsInUse += 1
          totalStallsAvailable -= 1
          tazTreeMap
            .getTAZ(parkingZone.tazId)
            .map{ taz =>
              ParkingStall(
                tazId = parkingZone.tazId,
                parkingZoneId = parkingZone.parkingZoneId,
                locationUTM = taz.coord,
                cost = 0.0,
                chargingPointType = None,
                pricingModel = None,
                parkingType = ParkingType.Workplace
              )
            }
        }
      }
    }
  }

  /**
    * releases a single stall in use at this Depot
    * @param parkingZoneId the ParkingZone id associated with the stall
    * @return None on failure
    */
  def releaseStall(parkingZoneId: Int): Option[Unit] = {
    if (parkingZoneId < 0 || rideHailParkingStalls.length <= parkingZoneId) None
    else {
      val parkingZone: ParkingZone = rideHailParkingStalls(parkingZoneId)
      val success = ParkingZone.releaseStall(parkingZone).value
      if (!success) None
      else Some {
          totalStallsInUse -= 1
          totalStallsAvailable += 1
          ()
        }

    }
  }
}

object RideHailDepotParkingManager {
  val SearchStartRadius: Double = 500.0   // meters
  val SearchMaxRadius: Int = 20000 // meters

  def apply(
             parkingFilePath: String,
             tazFilePath: String,
             valueOfTime: Double,
             tazTreeMap: TAZTreeMap,
             random: Random,
             boundingBox: Envelope,
             distFunction: (Location, Location) => Double
           ): RideHailDepotParkingManager = {
    new RideHailDepotParkingManager(
      parkingFilePath,
      tazFilePath,
      valueOfTime,
      tazTreeMap,
      random,
      boundingBox,
      distFunction
    )
  }

}