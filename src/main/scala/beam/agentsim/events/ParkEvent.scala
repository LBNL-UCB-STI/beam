package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.{ChargingType, ParkingType, PricingModel}
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import collection.JavaConverters._

/**HasPersonId is added as Matsim ScoringFunction for population requires it**/
case class ParkEvent(
  time: Double,
  driverId: String,
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  cost: Double,
  locationUTM: Coord,
  parkingType: ParkingType,
  pricingModel: PricingModel,
  chargingType: ChargingType
) extends Event(time)
    with ScalaEvent {
  import ParkEvent._

  def getDriverId: String = driverId

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_DRIVER_ID, driverId.toString)
    attr.put(ATTRIBUTE_COST, cost.toString)
    attr.put(ATTRIBUTE_LOCATION_X, locationUTM.getX.toString)
    attr.put(ATTRIBUTE_LOCATION_Y, locationUTM.getY.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, pricingModel.toString)
    attr.put(ATTRIBUTE_CHARGING_TYPE, chargingType.toString)
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)

    attr
  }
}

object ParkEvent {
  val EVENT_TYPE: String = "ParkEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_COST: String = "cost"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"

  def apply(time: Double, stall: ParkingStall, vehicleId: Id[Vehicle], driverId: String): ParkEvent =
    new ParkEvent(
      time,
      driverId,
      vehicleId,
      stall.attributes.tazId,
      stall.cost,
      stall.locationUTM,
      stall.attributes.parkingType,
      stall.attributes.pricingModel,
      stall.attributes.chargingType
    )

  def apply(genericEvent: GenericEvent): ParkEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala

    val time: Double = genericEvent.getTime
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val cost: Double = attr(ATTRIBUTE_COST).toDouble
    val locationUTM: Coord = new Coord(attr(ATTRIBUTE_LOCATION_X).toDouble, attr(ATTRIBUTE_LOCATION_Y).toDouble)
    val parkingType: ParkingType = ParkingType.fromString(attr(ATTRIBUTE_PARKING_TYPE))
    val pricingModel: PricingModel = PricingModel.fromString(attr(ATTRIBUTE_PRICING_MODEL))
    val chargingType: ChargingType = ChargingType.fromString(attr(ATTRIBUTE_CHARGING_TYPE))
    ParkEvent(time, driverId, vehicleId, tazId, cost, locationUTM, parkingType, pricingModel, chargingType)
  }
}
