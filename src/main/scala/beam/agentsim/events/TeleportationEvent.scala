package beam.agentsim.events

import beam.agentsim.agents.PersonAgent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event

import java.util
import java.util.concurrent.atomic.AtomicReference

case class TeleportationEvent(
  time: Double,
  person: Id[PersonAgent],
  departureTime: Int,
  arrivalTime: Int,
  startX: Double,
  startY: Double,
  endX: Double,
  endY: Double,
  currentTourMode: Option[String]
) extends Event(time)
    with ScalaEvent {
  import TeleportationEvent._

  override def getEventType: String = EVENT_TYPE

  private val filledAttrs: AtomicReference[util.Map[String, String]] =
    new AtomicReference[util.Map[String, String]](null)

  override def getAttributes: util.Map[String, String] = {
    if (filledAttrs.get() != null) filledAttrs.get()
    else {
      val attr = super.getAttributes

      attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime.toString)
      attr.put(ATTRIBUTE_PERSON, person.toString)
      attr.put(ATTRIBUTE_ARRIVAL_TIME, arrivalTime.toString)
      attr.put(ATTRIBUTE_START_COORDINATE_X, startX.toString)
      attr.put(ATTRIBUTE_START_COORDINATE_Y, startY.toString)
      attr.put(ATTRIBUTE_END_COORDINATE_X, endX.toString)
      attr.put(ATTRIBUTE_END_COORDINATE_Y, endY.toString)
      attr.put(ATTRIBUTE_CURRENT_TOUR_MODE, currentTourMode.getOrElse(""))

      filledAttrs.set(attr)
      attr
    }
  }
}

object TeleportationEvent {
  val EVENT_TYPE: String = "TeleportationEvent"

  val ATTRIBUTE_CURRENT_TOUR_MODE: String = "currentTourMode"

  val ATTRIBUTE_DEPARTURE_TIME: String = "departureTime"
  val ATTRIBUTE_PERSON: String = "person"
  val ATTRIBUTE_ARRIVAL_TIME: String = "arrivalTime"
  val ATTRIBUTE_START_COORDINATE_X: String = "startX"
  val ATTRIBUTE_START_COORDINATE_Y: String = "startY"
  val ATTRIBUTE_END_COORDINATE_X: String = "endX"
  val ATTRIBUTE_END_COORDINATE_Y: String = "endY"
}
