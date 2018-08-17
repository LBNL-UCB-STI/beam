package beam.agentsim.infrastructure

import akka.actor.Actor
import beam.agentsim.ResourceManager
import beam.agentsim.agents.PersonAgent
import beam.agentsim.infrastructure.ParkingManager.ParkingStockAttributes
import beam.agentsim.infrastructure.ParkingStall.{ChargingPreference, ReservedParkingType}
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes

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
    reservedFor: ReservedParkingType = ParkingStall.Any
  )

  case class DepotParkingInquiry(customerLocationUtm: Location, reservedFor: ReservedParkingType)
  case class DepotParkingInquiryResponse(mStall: Option[ParkingStall])

  case class ParkingInquiryResponse(stall: ParkingStall)

  // Use this to pass data from CSV or config file into the manager
  case class ParkingStockAttributes(val numSpacesPerTAZ: Int)
}
