package beam.utils.beamToVia

case class PathLinkEvent(time: Double,
                         eventType: String,
                         vehicle: String,
                         link: Int) {

  def toXml: scala.xml.Elem =
    <event time={time.toString} type={eventType} vehicle={vehicle} link={link.toString} />
}
