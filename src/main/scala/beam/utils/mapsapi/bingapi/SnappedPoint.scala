package beam.utils.mapsapi.bingapi

import beam.agentsim.infrastructure.geozone.WgsCoordinate

case class SnappedPoint(coordinate: WgsCoordinate, name: String, speedLimitInKph: Double)
