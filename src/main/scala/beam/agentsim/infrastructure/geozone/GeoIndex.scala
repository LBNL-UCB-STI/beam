package beam.agentsim.infrastructure.geozone

case class GeoIndex private[geozone] (value: String, resolution: Int)

object GeoIndex {
  def apply(value: String): GeoIndex = new GeoIndex(value, H3Wrapper.getResolution(value))
}
