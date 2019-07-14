package beam.utils.beamToVia.viaEvent

case class ViaPersonDepartureEvent(var time: Double, person: String, link: Int) extends ViaEvent {

  def toXml: scala.xml.Elem =
    <event time={timeString} type="departure" person={person} link={link.toString} legMode="car" />
}
