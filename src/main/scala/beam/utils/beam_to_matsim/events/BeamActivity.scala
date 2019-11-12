package beam.utils.beam_to_matsim.events

import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

object BeamActivityStart {
  val EVENT_TYPE: String = "actstart"

  def apply(genericEvent: Event): BeamActivityStart = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val activity = BeamActivity(genericEvent)

    new BeamActivityStart(activity)
  }
}

case class BeamActivityStart(ba: BeamActivity) extends BeamActivity(ba.time, ba.personId, ba.linkId, ba.activityType)

object BeamActivityEnd {
  val EVENT_TYPE: String = "actend"

  def apply(genericEvent: Event): BeamActivityEnd = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val activity = BeamActivity(genericEvent)

    new BeamActivityEnd(activity)
  }
}

case class BeamActivityEnd(ba: BeamActivity) extends BeamActivity(ba.time, ba.personId, ba.linkId, ba.activityType)

object BeamActivity {
  val ATTRIBUTE_PERSON_ID = "person"
  val ATTRIBUTE_LINK_ID = "link"
  val ATTRIBUTE_ACTTYPE = "actType"

  def apply(genericEvent: Event): BeamActivity = {
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val time = genericEvent.getTime
    val personId: String = attr(ATTRIBUTE_PERSON_ID)
    val linkId: Int = attr(ATTRIBUTE_LINK_ID).toInt
    val activityType = attr(ATTRIBUTE_ACTTYPE)

    new BeamActivity(time, personId, linkId, activityType)
  }
}

// 	<event time="18488.0" type="actstart"   person="022802-2012001386215-0-6282252" link="56240" actType="Work" />
//	<event time="18533.0" type="actend"     person="022802-2012001386215-0-6282251" link="65256" actType="Home" />

class BeamActivity(override val time: Double, val personId: String, val linkId: Int, val activityType: String)
    extends BeamEvent
