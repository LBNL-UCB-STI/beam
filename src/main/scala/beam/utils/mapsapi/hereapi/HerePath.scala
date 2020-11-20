package beam.utils.mapsapi.hereapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

private[hereapi] case class HerePath(coordinates: Seq[WgsCoordinate], spans: Seq[HereSpan])
