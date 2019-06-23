package beam.utils.beamToVia

sealed trait ViaTraverseLinkEventType
object EnteredLink extends ViaTraverseLinkEventType
object LeftLink extends ViaTraverseLinkEventType

case class ViaTraverseLinkEvent(time: Double, vehicle: String, eventType: ViaTraverseLinkEventType, link: Int)
    extends ViaEvent {

  def toXml: scala.xml.Elem =
    eventType match {
      case EnteredLink => <event time={time.toString} type="entered link" vehicle={vehicle} link={link.toString} />
      case LeftLink    => <event time={time.toString} type="left link" vehicle={vehicle} link={link.toString} />
    }

}
