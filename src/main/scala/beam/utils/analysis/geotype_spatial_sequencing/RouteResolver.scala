package beam.utils.analysis.geotype_spatial_sequencing

import beam.utils.ProfilingUtils
import com.graphhopper.util.shapes.GHPoint
import com.graphhopper.{GHRequest, GHResponse, GraphHopper}
import com.typesafe.scalalogging.LazyLogging

class RouteResolver(val ghLocation: String) extends LazyLogging {
  private val gh: GraphHopper = {
    ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
      val tempGh = new GraphHopper()
      tempGh.setGraphHopperLocation(ghLocation)
      tempGh.importOrLoad()
      tempGh
    }
  }

  def route(origin: CencusTrack, dest: CencusTrack): GHResponse = {
    val request: GHRequest =
      new GHRequest(new GHPoint(origin.latitude, origin.longitude), new GHPoint(dest.latitude, dest.longitude))
    request
      .setVehicle("car")
      .setWeighting("fastest")
      .setAlgorithm("")
      .setLocale("en")
      .setPointHints(java.util.Collections.emptyList())
      .getHints
      .put("calc_points", true)
      .put("instructions", true)
      .put("way_point_max_distance", 1)
    gh.route(request)
  }
}
