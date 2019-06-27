package beam.utils.beamToVia.viaEvent

trait ViaEvent {
  val time: Double
  val link: Int
  def toXml: scala.xml.Elem
}
