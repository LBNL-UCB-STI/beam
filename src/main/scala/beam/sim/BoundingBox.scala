package beam.sim

/**
  * BEAM
  */
class BoundingBox(val crs: String) {
  var minX = 1e7
  var minY = 1e7
  var maxX = -1e7
  var maxY = -1e7
}
