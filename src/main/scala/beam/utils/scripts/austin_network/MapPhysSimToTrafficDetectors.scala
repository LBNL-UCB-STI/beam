package beam.utils.scripts.austin_network


import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object MapPhysSimToTrafficDetectors {

  val log=new Logging()
  def main(args: Array[String]): Unit = {

    import AustinUtils._

    log.info("start")
    val splitSizeInMeters = 10

    val physsimNetwork = getPhysSimNetwork("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-25_07-21-44_tmc\\output_network.xml.gz")
    val outputFileName="E:\\work\\austin\\physSimNetworkLinkIdsWithTrafficDetectors.csv"

    log.info("data read")
    val linkIdsBothDirections: Vector[String] = getPhysSimNetworkIdsWithTrafficDectors(splitSizeInMeters, physsimNetwork)

    writeFile(linkIdsBothDirections,outputFileName,Some("linkId"))

  }

  private def getPhysSimNetworkIdsWithTrafficDectors(splitSizeInMeters: Int, physsimNetwork: Network) = {
    import AustinUtils._
    val trafficDetectors = VisualizeVolumeStations.getCoordinatesTrafficCountsFile("E:\\work\\austin\\Traffic_Detectors.csv", 0, 1)
    val physSimDataPoints = physsimNetwork.getLinks.values().asScala.toVector.flatMap { link =>
      DataVector(DataId(link.getId.toString), link.getFromNode.getCoord, link.getToNode.getCoord, false).produceSpeedDataPointFromSpeedVector(splitSizeInMeters)
    }

    log.info("physSimDataPoints complete")
    val quadTree = getQuadTree(physSimDataPoints)
    log.info("quadTree complete")

    val trafficDataPoints = trafficDetectors.map { case (id, coord) =>
      DataPoint(id, getGeoUtils.wgs2Utm(coord), ArrayBuffer.empty)
    }

    assignDataPointsToQuadTree(quadTree, trafficDataPoints)
    log.info("assignDataPointsToQuadTree complete")

    val link = physsimNetwork.getLinks.get(DataId("1").getLinkId)

    val links = physSimDataPoints.filter(dataPoint => dataPoint.closestAttractedDataPoint.nonEmpty)
    val linkIdsWithTrafficDetectors = links.map(dataPoint => physsimNetwork.getLinks.get(dataPoint.id.getLinkId))
    val linkIdsBothDirections = AustinUtils.getBothDirectionsOfSelectedLinks(linkIdsWithTrafficDetectors).map(_.getId.toString).toVector
    linkIdsBothDirections
  }
}
