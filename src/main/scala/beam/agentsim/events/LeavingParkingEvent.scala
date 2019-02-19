package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.ParkingStall.{ChargingType, ParkingType, PricingModel}
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

case class LeavingParkingEvent(
  time: Double,
  personId: Id[Person],
  vehicleId: Id[Vehicle],
  tazId: Id[TAZ],
  score: Double,
  parkingType: ParkingType,
  pricingModel: PricingModel,
  chargingType: ChargingType
) extends Event(time)
    with HasPersonId {
  import LeavingParkingEvent._

  override def getPersonId: Id[Person] = personId

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_SCORE, score.toString)
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_PARKING_TYPE, parkingType.toString)
    attr.put(ATTRIBUTE_PRICING_MODEL, pricingModel.toString)
    attr.put(ATTRIBUTE_CHARGING_TYPE, chargingType.toString)
    attr.put(ATTRIBUTE_PARKING_TAZ, tazId.toString)

    attr
  }
}

object LeavingParkingEvent {
  val EVENT_TYPE: String = "LeavingParkingEvent"
  //    String ATTRIBUTE_PARKING_ID = "parkingId";
  val ATTRIBUTE_SCORE: String = "score"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"

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
      stall.attributes.tazId,
      score,
      stall.attributes.parkingType,
      stall.attributes.pricingModel,
      stall.attributes.chargingType
    )
}
