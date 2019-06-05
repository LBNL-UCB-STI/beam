package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.{Coord, Id}

case class ParkingStall(
  tazId: Id[TAZ],
  parkingZoneId: Int,
  locationUTM: Location,
  cost: Double,
  chargingPointType: Option[ChargingPointType],
  pricingModel: Option[PricingModel],
  parkingType: ParkingType
)

object ParkingStall {

  val CostOfEmergencyStall: Double = 100000.0 // $1000.00 stall used as an emergency when no stalls were found

  /**
    * for testing purposes and trivial parking functionality, produces a stall directly at the provided location which has no cost and is available
    * @param coord the location for the stall
    * @return a new parking stall with the default Id[Taz] and parkingZoneId
    */
  def defaultStall(coord: Coord): ParkingStall = ParkingStall(
    tazId = TAZ.DefaultTAZId,
    parkingZoneId = ParkingZone.DefaultParkingZoneId,
    locationUTM = coord,
    cost = 0.0,
    chargingPointType = None,
    pricingModel = None,
    parkingType = ParkingType.Public
  )
}
