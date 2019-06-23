package beam.utils.beamToVia

case class ViaLeftLinkEvent42(time: Double,
                            vehicle: String,
                            link: Int) extends ViaEvent {

  def toXml: scala.xml.Elem =
      <event time={time.toString} type="left link" vehicle={vehicle} link={link.toString} />
}
