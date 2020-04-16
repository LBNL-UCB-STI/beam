package beam.agentsim.infrastructure.geozone

import beam.agentsim.infrastructure.geozone.GeoZone.GeoZoneContent

trait GeoZoneHexGenerator {
  def generateSummary(): GeoZoneSummary
  def generateContent(): GeoZoneContent
}
