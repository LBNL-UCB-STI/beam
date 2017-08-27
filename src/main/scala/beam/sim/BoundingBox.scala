package beam.sim

import com.vividsolutions.jts.geom.Coordinate
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS

/**
  * BEAM
  */
object BoundingBox {
}
class BoundingBox() {
  var minX = 1e6
  var minY = 1e6
  var maxX = -1e6
  var maxY = -1e6
  val transform = CRS.findMathTransform(CRS.decode("EPSG:4326", true), CRS.decode("EPSG:26910", true), false)

  def observeCoord(coord: Coordinate): Unit ={
    val pos = new DirectPosition2D(coord.x, coord.y)
    val posTransformed = new DirectPosition2D(coord.x,coord.y)
    if (coord.x <= 180.0 & coord.x >= -180.0 & coord.y > -90.0 & coord.y < 90.0) {
      transform.transform(pos, posTransformed)
    }
    if (posTransformed.x < minX) minX = posTransformed.x
    if (posTransformed.y < minY) minY = posTransformed.y
    if (posTransformed.x > maxX) maxX = posTransformed.x
    if (posTransformed.y > maxY) maxY = posTransformed.y
  }
}
