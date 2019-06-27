package beam.utils.beamToVia.beamEvent

trait BeamEvent {
  val time: Double

  def toXml: xml.Elem
}
