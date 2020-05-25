package beam.utils.hereapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

case class HereSegment(coordinates: Seq[WgsCoordinate], lengthInMeters: Int, speedLimitInKph: Option[Int])
