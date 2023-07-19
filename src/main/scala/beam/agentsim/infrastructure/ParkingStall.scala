package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingAlternative
import beam.agentsim.infrastructure.parking.{ParkingType, _}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import com.vividsolutions.jts.geom.Envelope
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
  reservedFor: ReservedFor
)

object ParkingStall {

  val CostOfEmergencyStallInDollars: Double = 50.0

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
      parkingZone.reservedFor
    )
  }

  /**
    * for testing purposes and trivial parking functionality, produces a stall directly at the provided location which has no cost and is available
    *
    * @param coord the location for the stall
    * @return a new parking stall with the default Id[Taz] and parkingZoneId
    */
  def defaultStall(coord: Coord): (ParkingStall, ParkingZone) = {
    val newStall = ParkingStall(
      tazId = TAZ.DefaultTAZId,
      parkingZoneId = ParkingZone.DefaultParkingZone.parkingZoneId,
      locationUTM = coord,
      costInDollars = 0.0,
      chargingPointType = None,
      pricingModel = None,
      parkingType = ParkingType.Public,
      reservedFor = VehicleManager.AnyManager
    )
    (newStall, ParkingZone.DefaultParkingZone)
  }

  /**
    * take a stall from the infinite parking zone, with a random location by default from planet-wide UTM values
    *
    * @param generateRandomLocationUsingThis        random number generator
    * @param costInDollars the cost of this stall
    * @return a stall that costs a lot but at least it exists. it's coordinate can be anywhere on the planet. for routing, the nearest link should be found using Beam Geotools.
    */
  def lastResortStall(
    location: Location,
    random: Random,
    costInDollars: Double = CostOfEmergencyStallInDollars
  ): (ParkingStall, ParkingZone) = {
    val boundingBox = new Envelope(
      location.getX + 2000,
      location.getX - 2000,
      location.getY + 2000,
      location.getY - 2000
    )
    val x = random.nextDouble() * (boundingBox.getMaxX - boundingBox.getMinX) + boundingBox.getMinX
    val y = random.nextDouble() * (boundingBox.getMaxY - boundingBox.getMinY) + boundingBox.getMinY
    val stallLocation = new Coord(x, y)
    ParkingStall(
      tazId = TAZ.EmergencyTAZId,
      parkingZoneId = ParkingZone.DefaultParkingZone.parkingZoneId,
      locationUTM = stallLocation,
      costInDollars = costInDollars,
      chargingPointType = None,
      pricingModel = Some { PricingModel.FlatFee(costInDollars.toInt) },
      parkingType = ParkingType.Public,
      reservedFor = VehicleManager.AnyManager
    ) -> ParkingZone.DefaultParkingZone
  }

  def obstructiveStallAtLocation(
    location: Location,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    costInDollars: Double = CostOfEmergencyStallInDollars
  ): (ParkingStall, ParkingZone) = {
    ParkingStall(
      tazId = tazId,
      parkingZoneId = ParkingZone.ObstructiveParkingZone.parkingZoneId,
      locationUTM = location,
      costInDollars = costInDollars,
      chargingPointType = None,
      pricingModel = Some { PricingModel.FlatFee(costInDollars.toInt) },
      parkingType = parkingType,
      reservedFor = VehicleManager.AnyManager
    ) -> ParkingZone.ObstructiveParkingZone
  }

  def defaultStallAtLocation(
    location: Location,
    tazId: Id[TAZ],
    parkingType: ParkingType,
    costInDollars: Double = CostOfEmergencyStallInDollars
  ): (ParkingStall, ParkingZone) = {
    ParkingStall(
      tazId = tazId,
      parkingZoneId = ParkingZone.DefaultParkingZone.parkingZoneId,
      locationUTM = location,
      costInDollars = costInDollars,
      chargingPointType = None,
      pricingModel = Some {
        PricingModel.FlatFee(costInDollars.toInt)
      },
      parkingType = parkingType,
      reservedFor = VehicleManager.AnyManager
    ) -> ParkingZone.DefaultParkingZone
  }

  //#Art

  /**
    * Convenience method to convert a [[ParkingAlternative]] to a [[ParkingStall]]
    *
    * @param parkingAlternative Parking Alternative
    * @return
    */
  def fromParkingAlternative(tazId: Id[TAZ], parkingAlternative: ParkingAlternative): ParkingStall = {
    ParkingStall(
      tazId,
      parkingAlternative.parkingZone.parkingZoneId,
      parkingAlternative.coord,
      parkingAlternative.costInDollars,
      parkingAlternative.parkingZone.chargingPointType,
      None,
      parkingAlternative.parkingType,
      parkingAlternative.parkingZone.reservedFor
    )
  }
}
