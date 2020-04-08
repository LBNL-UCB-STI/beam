package beam.agentsim.infrastructure.h3

case class H3Point(latitude: Double, longitude: Double) {

  override def toString: String = s"($latitude,$longitude)"

}
