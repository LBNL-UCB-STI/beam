package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}

case class ParkingStall(
                         tazId: Id[TAZ],
                         parkingZoneId: Int,
                         locationUTM: Location,
                         cost: Double,
                         chargingPoint: Option[ChargingPointType],
                         pricingModel: Option[PricingModel],
                         parkingType: ParkingType
)

object ParkingStall {
  val emptyParkingStall: ParkingStall = DefaultStall(new Coord())

  val CostOfEmergencyStall: Double = 1000.0 // used as an emergency when no stalls were found

  /**
    * take a stall from the infinite parking zone
    * @param location location of this inquiry
    * @return a stall that costs a lot but at least it exists
    */
  def DefaultStall(location: Location) =
    ParkingStall(
      tazId = Id.create("NA", classOf[TAZ]),
      parkingZoneId = ParkingZone.DefaultParkingZoneId,
      locationUTM = location,
      cost = CostOfEmergencyStall,
      chargingPoint = None,
      pricingModel = None,
      parkingType = ParkingType.Public
    )
}
