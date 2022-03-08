package beam.analysis.cartraveltime

import beam.agentsim.events.PathTraversalEvent
import beam.sim.{CircularGeofence, Geofence}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Calibration.StudyArea
import org.matsim.api.core.v01.Coord

trait TripFilter {
  def considerPathTraversal(pte: PathTraversalEvent): Boolean
}

object TakeAllTripsTripFilter extends TripFilter {
  override def considerPathTraversal(pte: PathTraversalEvent): Boolean = true
}

class StudyAreaTripFilter(val studyArea: StudyArea, val geoUtils: GeoUtils) extends TripFilter {
  require(studyArea.enabled, "Instance of `StudyAreaTripFilter` should be created only if `studyArea.enabled=true`")
  require(
    studyArea.lon != 0,
    "`beam.calibration.studyArea.enabled` is true, but `beam.calibration.studyArea.lon` is not set"
  )
  require(
    studyArea.lat != 0,
    "`beam.calibration.studyArea.enabled` is true, but `beam.calibration.studyArea.lat` is not set"
  )
  require(
    studyArea.radius > 0,
    "`beam.calibration.studyArea.enabled` is true, but `beam.calibration.studyArea.radius` is not set"
  )

  // latitude is Y
  // longitude is X
  private val wgsCoord = new Coord(studyArea.lon, studyArea.lat)
  require(!GeoUtils.isInvalidWgsCoordinate(wgsCoord), s"Provided WGS coordinate $wgsCoord is not valid")
  private val utmCoord = geoUtils.wgs2Utm(new Coord(studyArea.lon, studyArea.lat))
  private val geoFence: Geofence = CircularGeofence(utmCoord.getX, utmCoord.getY, studyArea.radius)

  override def considerPathTraversal(pte: PathTraversalEvent): Boolean = {
    val startUTMCoord = geoUtils.wgs2Utm(new Coord(pte.startX, pte.startY))
    val endUTMCoord = geoUtils.wgs2Utm(new Coord(pte.endX, pte.endY))
    val hasStart = geoFence.contains(startUTMCoord)
    val hasEnd = geoFence.contains(endUTMCoord)
    hasStart && hasEnd
  }
}
