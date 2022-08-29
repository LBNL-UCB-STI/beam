package beam.utils.beam_to_matsim.via_event

trait ViaEvent {
  var time: Double
  val link: Int
  def toXml: scala.xml.Elem
  def toXmlString: String
  def timeString: String = time.toInt + ".0"
}
