package beam.utils.beamToVia.viaEvent

trait ViaEvent {
  var time: Double
  val link: Int
  def toXml: scala.xml.Elem
  def timeString: String = time.toString // time.toInt + ".0"
}
