
import beam.sim.common.GeoUtils
import org.geotools.geometry.DirectPosition2D
import org.matsim.api.core.v01.Coord

import scala.math.{Pi, cos, sin}

val time: Double = 20000
val location: Coord = new Coord(540000, 4160000)
val intensity: Double = 1.0
val pointProcessType: String = "CHOICE"
val radialLength: Double = 1000
val paceInTicksPerFrame: Double = 1
val numRays: Int = 8
val directionOut: Boolean = true
val numFrames: Int = 10
val doTransform = true

val radiusFromOrigin: Vector[Double] = (for (i <- 0 to numFrames - 1) yield (radialLength * i / (numFrames - 1))).toVector
val deltaRadian = 2.0 * Pi / numRays
val vizData = for (rayIndex <- 0 to numRays - 1) yield {
  for (frameIndex <- 0 to numFrames - 1) yield {
    val len = radiusFromOrigin(frameIndex)
    var x = location.getX + len * cos(deltaRadian * rayIndex)
    var y = location.getY + len * sin(deltaRadian * rayIndex)
    if (doTransform) {
      val thePos = new DirectPosition2D(x, y)
      val thePosTransformed = GeoUtils.Transformer(new Coord(x, y))
      x = thePosTransformed.getX
      y = thePosTransformed.getY
    }
    s"""\"shp\": [%.6f,%.6f],\"tim\":""".format(x, y) + (time + paceInTicksPerFrame * frameIndex)
  }
}
val res = ((for (x <- vizData) yield (x.mkString(","))).mkString("----"))
//val resultStr = for(strV <- vizData)yield( strV.mkString(",") ).mkString(",").mkString(",")
