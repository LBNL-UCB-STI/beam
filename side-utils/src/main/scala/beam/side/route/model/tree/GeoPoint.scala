package beam.side.route.model.tree

import Math._
import beam.side.route.model.tree.KDimension._
import beam.side.route.model.tree.Dimensional.XYZ._

class GeoPoint(x: Double, y: Double, z: Double) extends PointD[Double, XYZ] {
  def this(lon: Double, lat: Double) {
    this(
      cos(toRadians(lat)) * cos(toRadians(lon)),
      cos(toRadians(lat)) * sin(toRadians(lon)),
      sin(toRadians(lat))
    )
  }

  override def apply(coord: XYZ#COORD): Double = coord match {
    case X => x
    case Y => y
    case Z => z
  }
}
