package beam.router.graphhopper

//import java.io.File

import beam.utils.ProfilingUtils
import com.graphhopper.config.{CHProfile, Profile}
import com.graphhopper.{GHRequest, GHResponse, GraphHopper}
//import com.graphhopper.reader.dem.MultiSourceElevationProvider
import com.graphhopper.util.shapes.GHPoint
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord

class GraphHopperRouteResolver(val ghLocation: String) extends LazyLogging {
  //private val gh: GraphHopper = {
  //  ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
  //    val tempGh = new GraphHopper()
  //    val elevationTempFolder = new File(ghLocation + "/elevation_provider")
  //    if (!elevationTempFolder.exists()) {
  //      elevationTempFolder.mkdir()
  //      logger.info(s"elevationTempFolder does not exist, created it on path: ${elevationTempFolder.getAbsolutePath}")
  //    }
  //    tempGh.setElevationProvider(new MultiSourceElevationProvider(elevationTempFolder.getAbsolutePath))
  //    tempGh.setGraphHopperLocation(ghLocation)
  //    tempGh.importOrLoad()
  //    tempGh
  //  }
  //}

  private val gh: GraphHopper = {
    ProfilingUtils.timed("Initialize GraphHopper", x => logger.info(x)) {
      val tempGh = new GraphHopper()
      tempGh.setGraphHopperLocation(ghLocation)
      tempGh.setProfiles(new Profile("car"))
      tempGh.getCHPreparationHandler.setCHProfiles(new CHProfile("car"))
      tempGh.importOrLoad()
      tempGh
    }
  }

  def route(originWGS: Coord, destWGS: Coord): GHResponse = {
    val request: GHRequest =
      new GHRequest(
        new GHPoint(originWGS.getY, originWGS.getX),
        new GHPoint(destWGS.getY, destWGS.getX)
      )
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
