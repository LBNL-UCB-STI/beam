package beam.utils.beam_to_matsim.via_event

case class ViaPersonDepartureEvent(var time: Double, person: String, link: Int) extends ViaEvent {

  def toXmlString: String =
    s"""<event time=$timeString type="departure" person=$person link=${link.toString} legMode="car" />"""

  def toXml: scala.xml.Elem =
    <event time={timeString} type="departure" person={person} link={link.toString} legMode="car" />
}
