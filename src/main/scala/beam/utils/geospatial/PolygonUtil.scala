package beam.utils.geospatial

import com.vividsolutions.jts.geom.{Geometry => JtsGeometry}
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.geotools.MGC

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object PolygonUtil {

  def findPolygonViaQuadTree[A, G <: JtsGeometry](
    quadTree: QuadTree[(A, G)],
    coord: Coord,
    maxDistance: Double,
    delta: Double,
    distance: Double = 0
  ): Option[(A, G)] = {
    @tailrec
    def findPolygonViaQuadTree0(distance: Double): Option[(A, G)] = {
      val xs = if (distance == 0.0) {
        Seq(quadTree.getClosest(coord.getX, coord.getY))
      } else {
        quadTree.getDisk(coord.getX, coord.getY, distance).asScala
      }
      xs.find { case (_, polygon) => polygon.contains(MGC.coord2Point(coord)) } match {
        case Some(value) =>
          Some(value)
        case None if distance > maxDistance =>
          None
        case None =>
          findPolygonViaQuadTree0(distance + delta)
      }
    }
    findPolygonViaQuadTree0(distance)
  }
}
