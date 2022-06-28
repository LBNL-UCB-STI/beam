package beam.utils.map

case class PointInfo(offset: Double, geofenceRadius: Double) {
  val ratio: Double = if (geofenceRadius.equals(0d)) Double.NaN else offset / geofenceRadius
}
