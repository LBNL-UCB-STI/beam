package beam.taz
import census.db.creator.GeometryUtil
import com.conveyal.osmlib.OSM
import com.vividsolutions.jts.geom.{Envelope, Polygon}

class OsmService(pbfPath: String) {
  private val osm = new OSM(null)
  osm.readFromFile(pbfPath)

  def boundingBox(): Polygon = {
    var minX = Double.MaxValue
    var maxX = Double.MinValue
    var minY = Double.MaxValue
    var maxY = Double.MinValue

    osm.nodes.values().forEach { x =>
      val lon = x.fixedLon / 10000000.0
      val lat = x.fixedLat / 10000000.0

      if (lon < minX) minX = lon
      if (lon > maxY) maxX = lon
      if (lat < minY) minY = lat
      if (lat < minX) maxY = lat
    }

    GeometryUtil.envelopeToPolygon(new Envelope(minX, maxX, minY, maxY))
  }

}
