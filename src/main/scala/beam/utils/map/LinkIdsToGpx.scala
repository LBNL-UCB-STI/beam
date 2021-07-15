package beam.utils.map

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
    val linkMap = network.getLinks

    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }

    val source = scala.io.Source.fromFile(pathToLinkIds)
    try {
      val links = source
        .getLines()
        .flatMap { linkIds =>
          linkIds.split(",").map { linkIdStr =>
            linkMap.get(Id.createLinkId(linkIdStr))
          }
        }
        .toArray

      val linkId2WgsCoord = links.map { link =>
        val loc = link.asInstanceOf[BasicLocation[Link]]
        val utmCoord = loc.getCoord
        val wgsCoord = geoUtils.utm2Wgs.transform(utmCoord)
        link.getId.toString -> wgsCoord
      }

      val gpxPoints = linkId2WgsCoord.map { case (linkId, wgsCoord) => GpxPoint(linkId, wgsCoord) }.toIterable
      GpxWriter.write(pathToGpx, gpxPoints)
      println(s"$pathToGpx is written.")
      val totalLength = links.map { link =>
        link.getLength
      }.sum
      println(s"Total length of links: $totalLength")
      val osmIds = links
        .map { link =>
          val osmId = Option(link.getAttributes)
            .flatMap(x => Option(x.getAttribute("origid")).map(_.toString))
          osmId -> (link.getId.toString, link.getLength)
        }
        .groupBy { case (k, _) => k }
        .map { case (k, v) =>
          k -> v.map(_._2).toList
        }

      osmIds.foreach { case (k, v) =>
        val length = v.map(_._2).sum
        println(s"$k => ${v.map(_._1).mkString(" ")}. Length: $length}")
      }
      println(s"OsmIds: $osmIds}")
    } finally {
      source.close()
    }
  }
}
