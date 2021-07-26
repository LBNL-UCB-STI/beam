package beam.utils.map

import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamHelper
import beam.sim.common.EdgeWithCoord
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Coord

object R5NetworkPlayground extends BeamHelper {

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(Array("--config", "production/sfbay/smart/smart-c-hightech.conf"), true)
    val beamConfig = BeamConfig(cfg)

    val nc = DefaultNetworkCoordinator(beamConfig)
    nc.init()

    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    val transportNetwork = nc.transportNetwork

    // split is null
    geoUtils.getNearestR5Edge(transportNetwork.streetLayer, new Coord(-123.358396043, 38.7670573007))
    // split is null
    geoUtils.getNearestR5Edge(transportNetwork.streetLayer, new Coord(-123.180062255, 38.7728279981))

    val gpxWriter = new GpxWriter("corners_production.gpx", geoUtils)

    val r: Array[(EdgeWithCoord, GpxPoint)] = geoUtils.getEdgesCloseToBoundingBox(transportNetwork.streetLayer)
    val corners = r.map { case (_, gpxPoint) => gpxPoint }
    try {
      corners.foreach(point => gpxWriter.drawMarker(point))
      // First 4 points reprecent LEFT, TOP, RIGHT, BOTTOM coordinates
      gpxWriter.drawRectangle(corners.take(4))

      r.foreach { case (edgeWithCoord, cornerPoint) =>
        val point = GpxPoint(
          s"${edgeWithCoord.edgeIndex}_${cornerPoint.name}",
          new Coord(edgeWithCoord.wgsCoord.x, edgeWithCoord.wgsCoord.y)
        )
        gpxWriter.drawMarker(point)
      }
    } finally {
      gpxWriter.close()
    }
  }
}
