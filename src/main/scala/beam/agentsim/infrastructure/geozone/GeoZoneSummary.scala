package beam.agentsim.infrastructure.geozone

case class GeoZoneSummary(items: Seq[GeoZoneSummaryItem])

case class GeoZoneSummaryItem(index: GeoIndex, size: Int)
