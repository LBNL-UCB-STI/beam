package beam.agentsim.infrastructure

import beam.agentsim.Resource
//import beam.agentsim.infrastructure.ParkingStall.{StallAttributes, StallValues}
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.infrastructure.parking.PricingModel
import beam.agentsim.infrastructure.parking.charging.ChargingPoint
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}


case class ParkingStall(
//  id: Id[ParkingStall],
  locationUTM: Location,
  cost: Double,
  chargingPoint: Option[ChargingPoint],
  pricingModel: Option[PricingModel]
//  stallValues: Option[StallValues]
)

object ParkingStall {
  val emptyId = Id.create("NA", classOf[ParkingStall])
}
