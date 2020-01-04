package beam.utils.analysis

import org.locationtech.jts.geom.LineString

package object geotype_spatial_sequencing {
  case class Trip(origin: String, dest: String, trips: Int)

  case class CencusTrack(
    state: String,
    country: String,
    tract: String,
    population: Int,
    latitude: Double,
    longitude: Double
  ) {
    val id: String = s"$state$country$tract"
  }

  case class OutputResult(origin: CencusTrack, dest: CencusTrack, lineString: LineString)
}
