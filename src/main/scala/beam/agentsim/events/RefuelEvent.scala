package beam.agentsim.events

import java.util

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

class RefuelEvent(
  tick: Double,
  stall: ParkingStall,
  energyInJoules: Double,
  sessionDuration: Double,
  vehId: Id[Vehicle]
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {
  import RefuelEvent._

  override def getPersonId: Id[Person] = Id.create(vehId, classOf[Person])

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_ENERGY_DELIVERED, energyInJoules.toString)
    attributes.put(ATTRIBUTE_SESSION_DURATION, sessionDuration.toString)
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attributes.put(ATTRIBUTE_COST, stall.cost.toString)
    attributes.put(ATTRIBUTE_LOCATION_X, stall.locationUTM.getX.toString)
    attributes.put(ATTRIBUTE_LOCATION_Y, stall.locationUTM.getY.toString)
    attributes.put(ATTRIBUTE_PARKING_TYPE, stall.attributes.parkingType.toString)
    attributes.put(ATTRIBUTE_PRICING_MODEL, stall.attributes.pricingModel.toString)
    attributes.put(ATTRIBUTE_CHARGING_TYPE, stall.attributes.chargingType.toString)
    attributes.put(ATTRIBUTE_PARKING_TAZ, stall.attributes.tazId.toString)
    attributes
  }
}

object RefuelEvent {
  val EVENT_TYPE: String = "RefuelEvent"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_ENERGY_DELIVERED: String = "fuel"
  val ATTRIBUTE_SESSION_DURATION: String = "duration"
  val ATTRIBUTE_COST: String = "cost"
  val ATTRIBUTE_LOCATION_X: String = "locationX"
  val ATTRIBUTE_LOCATION_Y: String = "locationY"
  val ATTRIBUTE_PARKING_TYPE: String = "parkingType"
  val ATTRIBUTE_PRICING_MODEL: String = "pricingModel"
  val ATTRIBUTE_CHARGING_TYPE: String = "chargingType"
  val ATTRIBUTE_PARKING_TAZ: String = "parkingTaz"
}
