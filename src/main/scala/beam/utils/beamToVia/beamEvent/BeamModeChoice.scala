package beam.utils.beamToVia.beamEvent

import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.xml.Elem

object BeamModeChoice {
  val EVENT_TYPE: String = "ModeChoice"
  val ATTRIBUTE_PERSON_ID = "person"
  val ATTRIBUTE_LINK_ID = "location"

  def apply(time: Double, personId: String, linkId:Int): BeamModeChoice =
    new BeamModeChoice(time, personId, linkId)

  def apply(genericEvent: Event): BeamModeChoice = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val time = genericEvent.getTime
    val personId: String = attr(ATTRIBUTE_PERSON_ID)
    val linkId:Int = attr(ATTRIBUTE_LINK_ID).toInt

    BeamModeChoice(time, personId, linkId)
  }
}

case class BeamModeChoice(time: Double, personId: String, linkId:Int) extends BeamEvent {
  override def toXml: Elem = <event time={time.toString} type="ModeChoice" person={personId} />
}
