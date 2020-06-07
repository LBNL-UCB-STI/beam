package beam.utils.mapsapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

case class Segment(
  coordinates: Seq[WgsCoordinate],
  lengthInMeters: Int,
  durationInSeconds: Option[Int] = None,
  speedLimitInKph: Option[Int] = None
)
