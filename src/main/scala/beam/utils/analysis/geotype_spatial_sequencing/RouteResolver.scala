package beam.utils.analysis.geotype_spatial_sequencing

import java.io.File

import beam.utils.ProfilingUtils
import com.graphhopper.reader.dem.MultiSourceElevationProvider
import com.graphhopper.util.shapes.GHPoint
import com.graphhopper.{GHRequest, GHResponse, GraphHopper}
import com.typesafe.scalalogging.LazyLogging

class RouteResolver(val ghLocation: String) extends LazyLogging {
  private val gh: GraphHopper = {
    ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
      val tempGh = new GraphHopper()
      val elevationTempFolder = new File(ghLocation + "/elevation_provider")
      if (!elevationTempFolder.exists()) {
        elevationTempFolder.mkdir()
        logger.info(s"elevationTempFolder does not exist, created it on path: ${elevationTempFolder.getAbsolutePath}")
      }
      tempGh.setElevationProvider(new MultiSourceElevationProvider(elevationTempFolder.getAbsolutePath))
      tempGh.setGraphHopperLocation(ghLocation)
      tempGh.importOrLoad()
      tempGh
    }
  }

  def route(origin: CencusTrack, dest: CencusTrack): GHResponse = {
    val request: GHRequest =
      new GHRequest(new GHPoint(origin.latitude, origin.longitude), new GHPoint(dest.latitude, dest.longitude))
    request
      .setAlgorithm("")
      .setLocale("en")
      .setPointHints(java.util.Collections.emptyList())
      .getHints
      .putObject("calc_points", true)
      .putObject("instructions", true)
      .putObject("way_point_max_distance", 1)
      .putObject("elevation", "true")
     gh.route(request)
  }
}
