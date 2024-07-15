package beam.agentsim.events

import beam.sim.RideHailFleetInitializer.FleetId

import java.util
import org.matsim.api.core.v01.events.Event

object FleetStoredElectricityEvent {
  val EVENT_TYPE: String = "FleetStoredElectricityEvent"
}

/**
  * Event capturing the electricity stored in the batteries of a fleet.
  *
  * @param tick Time at which the event is thrown
  * @param storedElectricityInJoules Electrical energy stored in the fleet.
  * @param storageCapacityInJoules Capacity of the to store electrical energy.
  */
class FleetStoredElectricityEvent(
  tick: Double,
  val fleetId: FleetId,
  val storedElectricityInJoules: Double,
  val storageCapacityInJoules: Double
) extends Event(tick)
    with ScalaEvent {

  import FleetStoredElectricityEvent._
  import ScalaEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_FLEET_ID, fleetId)
    attributes.put(ATTRIBUTE_STORED_ELECTRICITY_IN_JOULES, storedElectricityInJoules.toString)
    attributes.put(ATTRIBUTE_STORAGE_CAPACITY_IN_JOULES, storageCapacityInJoules.toString)
    attributes
  }
}
