package beam.utils.analysis.geotype_spatial_sequencing

import beam.utils.ProfilingUtils
import com.graphhopper.config.{CHProfileConfig, ProfileConfig}
import com.graphhopper.util.shapes.GHPoint
import com.graphhopper.{GHRequest, GHResponse, GraphHopper}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

class RouteResolverTAZ(val ghLocation: String) extends LazyLogging {
  private val gh: GraphHopper = {
    ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
      val tempGh = new GraphHopper()
      tempGh.setGraphHopperLocation(ghLocation)
      tempGh.setProfiles(new ProfileConfig("car"))
      tempGh.getCHPreparationHandler.setCHProfileConfigs(new CHProfileConfig("car"))
      tempGh.importOrLoad()
      tempGh
    }
  }

  def route(originWGS: Coord, destWGS: Coord): GHResponse = {
    val request: GHRequest =
      new GHRequest(new GHPoint(originWGS.getY, originWGS.getX), new GHPoint(destWGS.getY, destWGS.getX))
    request
      .setProfile("car")
      .setAlgorithm("")
      .setLocale("en")
      .setPointHints(java.util.Collections.emptyList())
      .getHints
      .putObject("calc_points", true)
      .putObject("instructions", true)
      .putObject("way_point_max_distance", 1)
    gh.route(request)
  }
}
