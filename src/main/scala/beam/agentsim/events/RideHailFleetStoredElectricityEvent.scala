package beam.agentsim.events

import java.util

import org.matsim.api.core.v01.events.Event

object RideHailFleetStoredElectricityEvent {
  val EVENT_TYPE: String = "RideHailFleetStoredElectricityEvent"
  val ATTRIBUTE_STORED_ELECTRICITY_IN_JOULES: String = "storedElectricityInJoules"
  val ATTRIBUTE_STORAGE_CAPACITY_IN_JOULES: String = "storageCapacityInJoules"
}

/**
  * Event capturing the electricity stored in the batteries of the ride hail fleet.
  *
  * @param tick Time at which the event is thrown
  * @param storedElectricityInJoules Electrical energy stored in the ride hail fleet.
  * @param storageCapacityInJoules Capacity of the ride hail fleet to store electrical energy.
  */
class RideHailFleetStoredElectricityEvent(
  tick: Double,
  val storedElectricityInJoules: Double,
  val storageCapacityInJoules: Double
) extends Event(tick)
    with ScalaEvent {

  import RideHailFleetStoredElectricityEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_STORED_ELECTRICITY_IN_JOULES, storedElectricityInJoules.toString)
    attributes.put(ATTRIBUTE_STORAGE_CAPACITY_IN_JOULES, storageCapacityInJoules.toString)

    attributes
  }
}
