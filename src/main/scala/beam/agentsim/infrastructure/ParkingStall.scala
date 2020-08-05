package beam.agentsim.infrastructure

import scala.util.Random
import beam.agentsim.infrastructure.parking.{ParkingType, ParkingZone, PricingModel}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.{Coord, Id}

case class ParkingStall(
  tazId: Id[TAZ],
  parkingZoneId: Int,
  locationUTM: Location,
  costInDollars: Double,
  chargingPointType: Option[ChargingPointType],
  pricingModel: Option[PricingModel],
  parkingType: ParkingType
)

object ParkingStall {

  val CostOfEmergencyStallInDollars: Double = 50.0

  /**
    * for testing purposes and trivial parking functionality, produces a stall directly at the provided location which has no cost and is available
    * @param coord the location for the stall
    * @return a new parking stall with the default Id[Taz] and parkingZoneId
    */
  def defaultStall(coord: Coord): ParkingStall = ParkingStall(
    tazId = TAZ.DefaultTAZId,
    parkingZoneId = ParkingZone.DefaultParkingZoneId,
    locationUTM = coord,
    costInDollars = 0.0,
    chargingPointType = None,
    pricingModel = None,
    parkingType = ParkingType.Public
  )

  /**
    * take a stall from the infinite parking zone, with a random location by default from planet-wide UTM values
    * @param random random number generator
    * @param boundingBox bounding box
    * @param costInDollars the cost of this stall
    *
    * @return a stall that costs a lot but at least it exists. it's coordinate can be anywhere on the planet. for routing, the nearest link should be found using Beam Geotools.
    */
  def lastResortStall(
    boundingBox: Envelope,
    random: Random = Random,
    costInDollars: Double = CostOfEmergencyStallInDollars,
    tazId: Id[TAZ] = TAZ.EmergencyTAZId,
  ): ParkingStall = {
    val x = random.nextDouble() * (boundingBox.getMaxX - boundingBox.getMinX) + boundingBox.getMinX
    val y = random.nextDouble() * (boundingBox.getMaxY - boundingBox.getMinY) + boundingBox.getMinY

    ParkingStall(
      tazId = tazId,
      parkingZoneId = ParkingZone.DefaultParkingZoneId,
      locationUTM = new Coord(x, y),
      costInDollars = costInDollars,
      chargingPointType = None,
      pricingModel = Some { PricingModel.FlatFee(costInDollars.toInt) },
      parkingType = ParkingType.Public
    )
  }

  /**
    * take a stall from the infinite parking zone, with a location at the request (e.g. traveler's home location).
    * This should only kick in when all other (potentially non-free, non-colocated) stalls in the search area are
    * exhausted
    *
    * @param locationUTM request location (home)
    *
    * @return a stall that is free and located at the person's home.
    */
  def defaultResidentialStall(
    locationUTM: Location
  ): ParkingStall = ParkingStall(
    tazId = TAZ.DefaultTAZId,
    parkingZoneId = ParkingZone.DefaultParkingZoneId,
    locationUTM = locationUTM,
    costInDollars = 0.0,
    chargingPointType = None,
    pricingModel = Some { PricingModel.FlatFee(0) },
    parkingType = ParkingType.Residential
  )
}
