package beam.agentsim.infrastructure.geozone

case class WgsCoordinate(latitude: Double, longitude: Double) {

  override def toString: String = s"($latitude,$longitude)"

}
