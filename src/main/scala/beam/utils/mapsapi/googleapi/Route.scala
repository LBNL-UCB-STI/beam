package beam.utils.mapsapi.googleapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.Segment

case class Route(
  origin: WgsCoordinate,
  destination: WgsCoordinate,
  distanceInMeters: Int,
  durationIntervalInSeconds: Int,
  durationInTrafficSeconds: Option[Int],
  segments: Seq[Segment] = Seq.empty
)
