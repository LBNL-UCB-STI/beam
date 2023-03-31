package beam.agentsim.infrastructure.geozone

import org.locationtech.jts.geom.Envelope

case class WgsBoundingBox(topLeft: WgsCoordinate, bottomRight: WgsCoordinate) {

  private val envelope = new Envelope(
    topLeft.longitude,
    bottomRight.longitude,
    topLeft.latitude,
    bottomRight.latitude
  )

  def contains(point: WgsCoordinate): Boolean = {
    envelope.contains(point.longitude, point.latitude)
  }

}
