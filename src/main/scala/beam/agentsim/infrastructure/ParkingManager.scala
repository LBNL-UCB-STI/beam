package beam.agentsim.infrastructure

import akka.actor.Actor
import beam.agentsim.ResourceManager
import beam.agentsim.agents.PersonAgent
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ParkingStall.{ChargingPreference, ReservedParkingType}
import beam.router.BeamRouter.Location
import org.apache.commons.lang.builder.HashCodeBuilder
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle

abstract class ParkingManager(
  parkingStockAttributes: ParkingStockAttributes
) extends Actor
    with ResourceManager[ParkingStall] {}

object ParkingManager {

  case class ParkingInquiry(
    customerId: Id[PersonAgent],
    customerLocationUtm: Location,
    destinationUtm: Location,
    activityType: String,
    valueOfTime: Double,
    chargingPreference: ChargingPreference,
    arrivalTime: Long,
    parkingDuration: Double,
    reservedFor: ReservedParkingType = ParkingStall.Any,
    reserveStall: Boolean = true
  ) {
    lazy val requestId: Int = new HashCodeBuilder().append(this).toHashCode
  }

  case class DepotParkingInquiry(
    vehicleId: Id[Vehicle],
    customerLocationUtm: Location,
    reservedFor: ReservedParkingType
  ) {
    lazy val requestId: Int = new HashCodeBuilder().append(this).toHashCode
  }

  case class DepotParkingInquiryResponse(maybeStall: Option[ParkingStall], requestId: Int)

  case class ParkingInquiryResponse(stall: ParkingStall, requestId: Int)

  // Use this to pass data from CSV or config file into the manager
  case class ParkingStockAttributes(numSpacesPerTAZ: Int)

}
