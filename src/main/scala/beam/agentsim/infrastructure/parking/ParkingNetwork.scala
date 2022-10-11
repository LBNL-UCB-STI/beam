package beam.agentsim.infrastructure.parking

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure._
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

abstract class ParkingNetwork(parkingZones: Map[Id[ParkingZoneId], ParkingZone]) extends LazyLogging {

  // Generic
  protected val searchFunctions: Option[InfrastructureFunctions]

  // Core
  protected var totalStallsInUse: Long = 0L
  protected var totalStallsAvailable: Long = parkingZones.map(_._2.stallsAvailable).sum

  /**
    * @param inquiry ParkingInquiry
    * @param parallelizationCounterOption Option[SimpleCounter]
    * @return
    */
  def processParkingInquiry(
    inquiry: ParkingInquiry,
    doNotReserveStallWithoutChargingPoint: Boolean = false,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): ParkingInquiryResponse = {
    logger.debug("Received parking inquiry: {}", inquiry)
    val ParkingZoneSearch.ParkingZoneSearchResult(parkingStall, parkingZone, _, _, _) =
      searchFunctions.map(_.searchForParkingStall(inquiry)).get
    // reserveStall is false when agent is only seeking pricing information
    if (inquiry.reserveStall && !doNotReserveStallWithoutChargingPoint) {
      logger.debug(
        s"reserving a ${if (parkingStall.chargingPointType.isDefined) "charging"
        else "non-charging"} stall for agent ${inquiry.requestId} in parkingZone ${parkingZone.parkingZoneId}"
      )
      // update the parking stall data
      val claimed: Boolean = searchFunctions.get.claimStall(parkingZone)
      if (claimed) {
        totalStallsInUse += 1
        totalStallsAvailable -= 1
      }
      if (totalStallsInUse % 1000 == 0)
        logger.debug("Parking stalls in use: {} available: {}", totalStallsInUse, totalStallsAvailable)
    }
    ParkingInquiryResponse(parkingStall, inquiry.requestId, inquiry.triggerId)
  }

  /**
    * @param release ReleaseParkingStall
    * @return
    */
  def processReleaseParkingStall(release: ReleaseParkingStall): Boolean = {
    val parkingZoneId = release.stall.parkingZoneId
    val released: Boolean = if (parkingZoneId == ParkingZone.DefaultParkingZoneId) {
      // this is an infinitely available resource; no update required
      logger.debug("Releasing a stall in the default/emergency zone")
      true
    } else if (!parkingZones.contains(parkingZoneId)) {
      logger.debug("Attempting to release stall in zone {} which is an illegal parking zone id", parkingZoneId)
      false
    } else {
      val releasedTemp: Boolean = searchFunctions.get.releaseStall(parkingZones(parkingZoneId))
      if (releasedTemp) {
        totalStallsInUse -= 1
        totalStallsAvailable += 1
      }
      releasedTemp
    }
    logger.debug("ReleaseParkingStall with {} available stalls ", totalStallsAvailable)
    released
  }

}
