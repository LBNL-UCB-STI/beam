package beam.utils.beamToVia

trait ViaEvent {
  val time:Double
  val link:Int
  def toXml: scala.xml.Elem
}
