package beam.utils.beamToVia.viaEvent

case class ViaPersonArrivalEvent(var time: Double, person: String, link: Int) extends ViaEvent {

  def toXml: scala.xml.Elem =
    <event time={timeString} type="arrival" person={person} link={link.toString} legMode="car" />
}
