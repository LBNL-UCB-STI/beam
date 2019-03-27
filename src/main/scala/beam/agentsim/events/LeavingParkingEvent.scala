package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging._
import beam.agentsim.infrastructure.parking._
import beam.agentsim.infrastructure.taz.TAZ
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

import collection.JavaConverters._

case class LeavingParkingEvent(
  time: Double,
  personId: Id[Person],
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  score: Double,
  parkingType: ParkingType,
  pricingModel: Option[PricingModel],
  ChargingPointType: Option[ChargingPointType]
) extends Event(time)
    with HasPersonId
    with ScalaEvent {
  import LeavingParkingEvent._

  override def getPersonId: Id[Person] = personId

  override def getEventType: String = EVENT_TYPE

  val pricingModelString = pricingModel.map { _.toString }.getOrElse("None")
  val chargingPointString = ChargingPointType.map { _.toString }.getOrElse("None")

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_SCORE, score.toString)
    attr.put(ATTRIBUTE_PERSON_ID, personId.toString)
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, optionalToString(pricingModel))
    attr.put(ATTRIBUTE_CHARGING_TYPE, optionalToString(chargingType))
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)

    attr
  }
}

object LeavingParkingEvent {

  def optionalToString[T](opt: Option[T]): String =
    opt match {
      case None => ""
      case Some(value) => value.toString
    }


  val EVENT_TYPE: String = "LeavingParkingEvent"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_SCORE: String = "score"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_PERSON_ID: String = "person"

  def apply(
    time: Double,
    stall: ParkingStall,
    score: Double,
    personId: Id[Person],
    vehId: Id[Vehicle]
  ): LeavingParkingEvent =
    new LeavingParkingEvent(
      time,
      personId,
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
    val personId: Id[Person] = Id.create(attr(ATTRIBUTE_PERSON_ID), classOf[Person])
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val tazId: Id[TAZ] = Id.create(attr(ATTRIBUTE_PARKING_TAZ), classOf[TAZ])
    val score: Double = attr(ATTRIBUTE_SCORE).toDouble
    val parkingType: ParkingType = ParkingType(attr(ATTRIBUTE_PARKING_TYPE))
    val pricingModel: Option[PricingModel] = PricingModel(attr(ATTRIBUTE_PRICING_MODEL), "0")  // TODO: cost (fee) should be an attribute of this event, but adding it will break a lot of tests
    val chargingType: Option[ChargingPointType] = ChargingPointType(attr(ATTRIBUTE_CHARGING_TYPE))
    LeavingParkingEvent(time, personId, vehicleId, tazId, score, parkingType, pricingModel, chargingType)
  }
}
