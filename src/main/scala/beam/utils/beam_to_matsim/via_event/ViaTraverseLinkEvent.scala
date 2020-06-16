package beam.utils.beam_to_matsim.via_event

sealed trait ViaTraverseLinkEventType
object EnteredLink extends ViaTraverseLinkEventType
object LeftLink extends ViaTraverseLinkEventType

object ViaTraverseLinkEvent {

  def entered(time: Double, vehicle: String, link: Int): ViaTraverseLinkEvent =
    ViaTraverseLinkEvent(time, vehicle, EnteredLink, link)

  def left(time: Double, vehicle: String, link: Int): ViaTraverseLinkEvent =
    ViaTraverseLinkEvent(time, vehicle, LeftLink, link)
}

case class ViaTraverseLinkEvent(var time: Double, vehicle: String, eventType: ViaTraverseLinkEventType, link: Int)
    extends ViaEvent {

  def toXmlString: String =
    eventType match {
      case EnteredLink => s"""<event time=$timeString type="entered link" vehicle=$vehicle link=${link.toString} />"""
      case LeftLink    => s"""<event time=$timeString type="left link" vehicle=$vehicle link=${link.toString} />"""
    }

  def toXml: scala.xml.Elem =
    eventType match {
      case EnteredLink => <event time={timeString} type="entered link" vehicle={vehicle} link={link.toString} />
      case LeftLink    => <event time={timeString} type="left link" vehicle={vehicle} link={link.toString} />
    }

}
