package beam.agentsim.events

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.sim.common.GeoUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

import java.util
import scala.collection.JavaConverters._

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
  import ScalaEvent._

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

    attr.put(ATTRIBUTE_VEHICLE, vehicleId.toString)
    attr.put(ATTRIBUTE_DRIVER, driverId)
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
  import ScalaEvent._
  val EVENT_TYPE: String = "ParkingEvent"

  def apply(
    time: Double,
    stall: ParkingStall,
    locationWGS: Coord,
    vehicleId: Id[Vehicle],
    driverId: String
  ): ParkingEvent = {
    stall.setParkingTime(time)
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

  def apply(genericEvent: Event): ParkingEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala

    val time: Double = genericEvent.getTime
    val driverId: String = attr(ATTRIBUTE_DRIVER)
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val locationWGS: Coord = new Coord(attr(ATTRIBUTE_LOCATION_X).toDouble, attr(ATTRIBUTE_LOCATION_Y).toDouble)
    val parkingType: ParkingType = ParkingType(attr(ATTRIBUTE_PARKING_TYPE))
    val pricingModel: Option[PricingModel] =
      attr.get(ATTRIBUTE_PRICING_MODEL).flatMap(PricingModel(_, attr.getOrElse(ATTRIBUTE_COST, "0")))
    val chargingPointType: Option[ChargingPointType] = attr.get(ATTRIBUTE_CHARGING_TYPE).flatMap(ChargingPointType(_))
    new ParkingEvent(time, driverId, vehicleId, tazId, locationWGS, parkingType, pricingModel, chargingPointType)
  }
}
