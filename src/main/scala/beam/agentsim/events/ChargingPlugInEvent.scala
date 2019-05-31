package beam.agentsim.events

import beam.agentsim.infrastructure.ParkingStall
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

class ChargingPlugInEvent(
  tick: Double,
  stall: ParkingStall,
  estSessionDuration: Double,
  vehId: Id[Vehicle]
) extends Event(tick)
    with HasPersonId
    with ScalaEvent {
  import ChargingPlugInEvent._

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = ???
}

object ChargingPlugInEvent {
  val EVENT_TYPE: String = "ChargingPlugInEvent"
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
