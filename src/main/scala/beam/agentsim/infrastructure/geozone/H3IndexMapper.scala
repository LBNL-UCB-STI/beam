package beam.agentsim.infrastructure.geozone

import beam.agentsim.infrastructure.geozone.GeoZone.GeoZoneContent

trait H3IndexMapper {
  def generateSummary(): GeoZoneSummary
  def generateContent(): GeoZoneContent
}
