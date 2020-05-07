package beam.utils.hereapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

case class HerePath (coordinates: Seq[WgsCoordinate], spans: Seq[HereSpan])
