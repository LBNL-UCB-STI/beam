package beam.utils.beam_to_matsim.events

import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

object BeamModeChoice {
  val EVENT_TYPE: String = "ModeChoice"
  val ATTRIBUTE_PERSON_ID = "person"
  val ATTRIBUTE_LINK_ID = "location"
  val ATTRIBUTE_MODE = "mode"

  def apply(genericEvent: Event): BeamModeChoice = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val time = genericEvent.getTime
    val personId: String = attr(ATTRIBUTE_PERSON_ID)
    val linkId: Int = attr(ATTRIBUTE_LINK_ID).toInt
    val mode = attr(ATTRIBUTE_MODE)

    new BeamModeChoice(time, personId, linkId, mode)
  }
}

case class BeamModeChoice(time: Double, personId: String, linkId: Int, mode: String) extends BeamEvent
