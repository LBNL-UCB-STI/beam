package beam.sim

import com.vividsolutions.jts.geom.Envelope

/**
  * BEAM
  */
class BoundingBox(val crs: String) {
  var minX = 1e6
  var minY = 1e6
  var maxX = -1e6
  var maxY = -1e6
}
