package beam.utils.beamToVia

case class ViaPersonArrivalEvent (time: Double,
                                  person: String,
                                  link: Int) extends ViaEvent {

  def toXml: scala.xml.Elem =
      <event time={time.toString} type="arrival" person={person} link={link.toString} />
}
