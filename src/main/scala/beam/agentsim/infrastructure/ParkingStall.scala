package beam.agentsim.infrastructure

import beam.agentsim.Resource
import beam.agentsim.infrastructure.parking.ParkingType
//import beam.agentsim.infrastructure.ParkingStall.{StallAttributes, StallValues}
import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.infrastructure.parking.PricingModel
import beam.agentsim.infrastructure.charging.ChargingPoint
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}

case class ParkingStall(
  tazId: Id[TAZ],
  parkingZoneId: Int,
  locationUTM: Location,
  cost: Double,
  chargingPoint: Option[ChargingPoint],
  pricingModel: Option[PricingModel],
  parkingType: ParkingType
//  stallValues: Option[StallValues]
)

object ParkingStall {
  val emptyId = Id.create("NA", classOf[ParkingStall])

  val emptyParkingStall: ParkingStall = DefaultStall(new Coord())

  /**
    * take a stall from the infinite parking zone
    * @param location location of this inquiry
    * @return a stall that costs a lot but at least it exists
    */
  def DefaultStall(location: Location) =
    ParkingStall(
      tazId = Id.create("NA", classOf[TAZ]),
      parkingZoneId = -1,
      locationUTM = location,
      cost = 1000D,
      chargingPoint = None,
      pricingModel = None,
      parkingType = ParkingType.Public
    )
}
