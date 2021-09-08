package beam.utils.mapsapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

case class Segment(
  coordinates: Seq[WgsCoordinate],
  lengthInMeters: Int,
  durationInSeconds: Option[Int] = None,
  speedLimitInMetersPerSecond: Option[Int] = None
) {
  private val meterPerHourToKmPerHourConversionFactor = 3.6d
  private val meterPerHourToMilesPerHourConversionFactor = 2.23694d

  def speedInKmPerHour: Option[Double] = {
    speedLimitInMetersPerSecond.map(_ * meterPerHourToKmPerHourConversionFactor)
  }

  def speedInMilesPerHour: Option[Double] = {
    speedLimitInMetersPerSecond.map(_ * meterPerHourToMilesPerHourConversionFactor)
  }

}
