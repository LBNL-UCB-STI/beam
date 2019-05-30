package beam.agentsim.infrastructure

import scala.util.Random

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

  /**
    * take a stall from the infinite parking zone, with a random location by default from planet-wide UTM values
    * @param random random number generator
    * @param minCoord coordinate representing the lower bounds on x,y values for this coordinate system
    * @param maxCoord coordinate representing the upper bounds on x,y values for this coordinate system
    * @param cost the cost of this stall
    *
    * @return a stall that costs a lot but at least it exists. it's coordinate can be anywhere on the planet. for routing, the nearest link should be found using Beam Geotools.
    */
  def lastResortStall(
    random: Random = Random,
    minCoord: Coord = new Coord(167000, 0),
    maxCoord: Coord = new Coord(833000, 10000000),
    cost: Double = CostOfEmergencyStall
  ): ParkingStall = {

    val x = (random.nextDouble() * (maxCoord.getX - minCoord.getX)) + minCoord.getX
    val y = (random.nextDouble() * (maxCoord.getY - minCoord.getY)) + minCoord.getY

    ParkingStall(
      tazId = TAZ.EmergencyTAZId,
      parkingZoneId = ParkingZone.DefaultParkingZoneId,
      locationUTM = new Coord(x, y),
      cost = cost,
      chargingPointType = None,
      pricingModel = Some { PricingModel.FlatFee(cost.toInt) },
      parkingType = ParkingType.Public
    )
  }
}
