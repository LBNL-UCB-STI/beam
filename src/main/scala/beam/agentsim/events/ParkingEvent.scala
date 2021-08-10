package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import collection.JavaConverters._

import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.LazyLogging

/** HasPersonId is added as Matsim ScoringFunction for population requires it* */
case class ParkingEvent(
  time: Double,
  driverId: String,
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  locationWGS: Coord,
  parkingType: ParkingType,
  pricingModel: Option[PricingModel],
  chargingPointType: Option[ChargingPointType]
) extends Event(time)
    with ScalaEvent
    with LazyLogging {

  import ParkingEvent._

  if (GeoUtils.isInvalidWgsCoordinate(locationWGS)) {
    logger.warn(s"ParkEvent should always receive WGS coordinates. [$locationWGS] looks like invalid")
  }

  def getDriverId: String = driverId

  override def getEventType: String = EVENT_TYPE

  def costInDollars: Double = pricingModel.map { _.costInDollars }.getOrElse(0.0)

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes

    val pricingModelString = pricingModel.map { _.toString }.getOrElse("None")
    val chargingPointString = chargingPointType.map { _.toString }.getOrElse("None")

    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_DRIVER_ID, driverId)
    attr.put(ATTRIBUTE_COST, costInDollars.toString)
    attr.put(ATTRIBUTE_LOCATION_X, locationWGS.getX.toString)
    attr.put(ATTRIBUTE_LOCATION_Y, locationWGS.getY.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, pricingModelString)
    attr.put(ATTRIBUTE_CHARGING_TYPE, chargingPointString)
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)

    attr
  }
}

object ParkingEvent {
  val EVENT_TYPE: String = "ParkingEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_COST: String = "cost"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingPointType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"

  def apply(
    time: Double,
    stall: ParkingStall,
    locationWGS: Coord,
    vehicleId: Id[Vehicle],
    driverId: String
  ): ParkingEvent = {
    new ParkingEvent(
      time = time,
      driverId = driverId,
      vehicleId = vehicleId,
      tazId = stall.tazId,
      locationWGS = locationWGS,
      parkingType = stall.parkingType,
      pricingModel = stall.pricingModel,
      chargingPointType = stall.chargingPointType
    )
  }

  def apply(genericEvent: GenericEvent): ParkingEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala

    val time: Double = genericEvent.getTime
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val cost: String = attr(ATTRIBUTE_COST)
    val locationWGS: Coord = new Coord(attr(ATTRIBUTE_LOCATION_X).toDouble, attr(ATTRIBUTE_LOCATION_Y).toDouble)
    val parkingType: ParkingType = ParkingType(attr(ATTRIBUTE_PARKING_TYPE))
    val pricingModel: Option[PricingModel] = PricingModel(attr(ATTRIBUTE_PRICING_MODEL), cost)
    val chargingPointType: Option[ChargingPointType] = ChargingPointType(attr(ATTRIBUTE_CHARGING_TYPE))
    new ParkingEvent(time, driverId, vehicleId, tazId, locationWGS, parkingType, pricingModel, chargingPointType)
  }
}
