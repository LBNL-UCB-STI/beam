package beam.utils

import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{BasicLocation, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

object LinkIdsToGpx {

  def main(args: Array[String]): Unit = {
    assert(args.length == 3)
    // Input for the tool is path to network and path to link ids file (comma separated link ids in one line)
    val pathToNetwork = args(0)
    val pathToLinkIds = args(1)

    // Output
    val pathToGpx = args(2)

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(pathToNetwork)
    val links = network.getLinks

    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    val linkId2WgsCoord = scala.io.Source.fromFile(pathToLinkIds).getLines().flatMap { linkIds =>
      linkIds.split(",").map { linkIdStr =>
        val link = links.get(Id.createLinkId(linkIdStr))
        val loc = link.asInstanceOf[BasicLocation[Link]]
        val utmCoord = loc.getCoord
        val wgsCoord = geoUtils.utm2Wgs.transform(utmCoord)
        linkIdStr -> wgsCoord
      }
    }
    val gpxPoints = linkId2WgsCoord.map { case (linkId, wgsCoord) => GpxPoint(linkId, wgsCoord) }.toIterable
    GpxWriter.write(pathToGpx, gpxPoints)
    println(s"$pathToGpx is written.")
  }
}
