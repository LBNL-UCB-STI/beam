package beam.utils.mapsapi.googleapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import com.google.maps.internal.PolylineEncoding
import com.google.maps.model.LatLng

import scala.jdk.CollectionConverters._

object GooglePolylineDecoder {

  def decode(polyline: String): Seq[WgsCoordinate] = {
    PolylineEncoding.decode(polyline).asScala.map { point: LatLng =>
      WgsCoordinate(latitude = point.lat, longitude = point.lng)
    }.toSeq
  }

}
