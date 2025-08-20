package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingAlternative
import beam.agentsim.infrastructure.parking.{ParkingType, _}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import org.locationtech.jts.geom.Envelope
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.Random

case class ParkingStall(
  tazId: Id[TAZ],
  parkingZoneId: Id[ParkingZoneId],
  locationUTM: Location,
  costInDollars: Double,
  chargingPointType: Option[ChargingPointType],
  pricingModel: Option[PricingModel],
  parkingType: ParkingType,
  activityType: ParkingActivityType,
  reservedFor: ReservedFor,
  link: Option[Link] = None
) {
  private var parkingTime: Double = 0.0

  // To set parking arrival time
  def setParkingTime(arrivalTime: Double): Unit = {
    parkingTime = arrivalTime
  }

  def getParkingTime: Double = parkingTime
}

object ParkingStall {

  def init(
    parkingZone: ParkingZone,
    tazId: Id[TAZ],
    location: Location,
    costInDollars: Double
  ): ParkingStall = {
    ParkingStall(
      tazId,
      parkingZone.parkingZoneId,
      location,
      costInDollars,
      parkingZone.chargingPointType,
      parkingZone.pricingModel,
      parkingZone.parkingType,
      ParkingActivityType.fromParkingType(parkingZone.parkingType),
      parkingZone.reservedFor
    )
  }

  private def createStallAtLocation(
    location: Location,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    parkingZone: ParkingZone,
    activityType: ParkingActivityType,
    costInDollars: Double
  ): (ParkingStall, ParkingZone) = {
    ParkingStall(
      tazId = tazId,
      parkingZoneId = parkingZone.parkingZoneId,
      locationUTM = location,
      costInDollars = costInDollars,
      chargingPointType = None,
      pricingModel = Some(PricingModel.FlatFee(costInDollars.toInt)),
      parkingType = parkingType,
      activityType = activityType,
      reservedFor = VehicleManager.AnyManager
    ) -> parkingZone
  }

  /**
    * for testing purposes and trivial parking functionality, produces a stall directly at the provided location which has no cost and is available
    *
    * @param coord the location for the stall
    * @return a new parking stall with the default Id[Taz] and parkingZoneId
    */
  def defaultStall(location: Location): (ParkingStall, ParkingZone) = {
    createStallAtLocation(
      location,
      TAZ.DefaultTAZId,
      ParkingType.Public,
      ParkingZone.DefaultParkingZone,
      ParkingActivityType.Miscellaneous,
      costInDollars = 0.0
    )
  }

  /**
    * take a stall from the infinite parking zone, with a location at the request (e.g. traveler's home location).
    * This should only kick in when all other (potentially non-free, non-colocated) stalls in the search area are
    * exhausted
    *
    * @param location request location (home)
    * @return a stall that is free and located at the person's home.
    */
  def defaultStall(
    location: Location,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    activityType: ParkingActivityType,
    costInDollars: Double
  ): (ParkingStall, ParkingZone) = {
    createStallAtLocation(
      location,
      tazId,
      parkingType,
      ParkingZone.DefaultParkingZone,
      activityType,
      costInDollars = costInDollars
    )
  }

  /**
    * take a stall from the infinite parking zone, with a random location by default from planet-wide UTM values
    *
    * @param random  random number generator
    * @param location   Coordinates
    * @param costInDollars the cost of this stall
    * @return a stall that costs a lot but at least it exists. it's coordinate can be anywhere on the planet. for routing, the nearest link should be found using Beam Geotools.
    */
  def lastResortStall(
    location: Location,
    random: Random,
    activityType: ParkingActivityType
  ): (ParkingStall, ParkingZone) = {
    val boundingBox = new Envelope(
      location.getX + 1000,
      location.getX - 1000,
      location.getY + 1000,
      location.getY - 1000
    )
    val x = random.nextDouble() * (boundingBox.getMaxX - boundingBox.getMinX) + boundingBox.getMinX
    val y = random.nextDouble() * (boundingBox.getMaxY - boundingBox.getMinY) + boundingBox.getMinY
    val stallLocation = new Coord(x, y)
    createStallAtLocation(
      stallLocation,
      TAZ.EmergencyTAZId,
      ParkingType.Public,
      ParkingZone.EmergencyParkingZone,
      activityType,
      costInDollars = 50.0
    )
  }

  /**
    * @param location
    * @param tazId
    * @param parkingType
    * @param activityType
    * @param costInDollars
    * @return
    */
  def obstructiveStallAtLocation(
    location: Location,
    tazId: Id[TAZ],
    activityType: ParkingActivityType,
    costInDollars: Double = 50.0
  ): (ParkingStall, ParkingZone) = {
    createStallAtLocation(
      location,
      tazId,
      ParkingType.DoubleParking,
      ParkingZone.ObstructiveParkingZone,
      activityType,
      costInDollars = costInDollars
    )
  }

  /**
    * Convenience method to convert a [[ParkingAlternative]] to a [[ParkingStall]]
    *
    * @param parkingAlternative Parking Alternative
    * @return
    */
  def fromParkingAlternative(
    tazId: Id[TAZ],
    activityType: String,
    parkingAlternative: ParkingAlternative
  ): ParkingStall = {
    ParkingStall(
      tazId,
      parkingAlternative.parkingZone.parkingZoneId,
      parkingAlternative.coord,
      parkingAlternative.costInDollars,
      parkingAlternative.parkingZone.chargingPointType,
      None,
      parkingAlternative.parkingType,
      ParkingActivityType.fromString(activityType),
      parkingAlternative.parkingZone.reservedFor
    )
  }
}
