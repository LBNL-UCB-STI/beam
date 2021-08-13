package beam.agentsim.events

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.vehicles.Vehicle

import java.util
import scala.collection.JavaConverters._

case class LeavingParkingEvent(
  time: Double,
  driverId: String,
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  score: Double,
  parkingType: ParkingType,
  pricingModel: Option[PricingModel],
  ChargingPointType: Option[ChargingPointType]
) extends Event(time)
    with ScalaEvent {
  import LeavingParkingEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_SCORE, score.toString)
    attr.put(ATTRIBUTE_DRIVER_ID, driverId)
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, optionalToString(pricingModel))
    attr.put(ATTRIBUTE_CHARGING_TYPE, optionalToString(ChargingPointType))
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)

    attr
  }
}

object LeavingParkingEvent {

  def optionalToString[T](opt: Option[T]): String =
    opt match {
      case None        => "None"
      case Some(value) => value.toString
    }

  val EVENT_TYPE: String = "LeavingParkingEvent"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_SCORE: String = "score"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingPointType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_DRIVER_ID: String = "driver"

  def apply(
    time: Double,
    stall: ParkingStall,
    score: Double,
    driverId: String,
    vehId: Id[Vehicle]
  ): LeavingParkingEvent =
    new LeavingParkingEvent(
      time,
      driverId,
      vehId,
      stall.tazId,
      score,
      stall.parkingType,
      stall.pricingModel,
      stall.chargingPointType
    )

  def apply(genericEvent: GenericEvent): LeavingParkingEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val personId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val score: Double = attr(ATTRIBUTE_SCORE).toDouble
    val parkingType: ParkingType = ParkingType(attr(ATTRIBUTE_PARKING_TYPE))
    val pricingModel: Option[PricingModel] = PricingModel(
      attr(ATTRIBUTE_PRICING_MODEL),
      "0"
    ) // TODO: cost (fee) should be an attribute of this event, but adding it will break a lot of tests
    val chargingPointType: Option[ChargingPointType] = ChargingPointType(attr(ATTRIBUTE_CHARGING_TYPE))
    LeavingParkingEvent(time, personId, vehicleId, tazId, score, parkingType, pricingModel, chargingPointType)
  }
}
