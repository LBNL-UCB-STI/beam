package beam.agentsim.events

import java.util

import org.matsim.api.core.v01.events.Event

class RideHailFleetStateEvent(
  tick: Double,
  evCavCount: Int,
  evNonCavCount: Int,
  nonEvCavCount: Int,
  nonEvNonCavCount: Int,
  vehicleType: String
) extends Event(tick)
    with ScalaEvent {
  import RideHailFleetStateEvent._
  import ScalaEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType)
    attributes.put(ATTRIBUTE_EV_CAV_COUNT, evCavCount.toString)
    attributes.put(ATTRIBUTE_EV_NON_CAV_COUNT, evNonCavCount.toString)
    attributes.put(ATTRIBUTE_NON_EV_CAV_COUNT, nonEvCavCount.toString)
    attributes.put(ATTRIBUTE_NON_EV_NON_CAV_COUNT, nonEvNonCavCount.toString)
    attributes
  }

}

object RideHailFleetStateEvent {
  val EVENT_TYPE: String = "RideHailFleetStateEvent"
}
