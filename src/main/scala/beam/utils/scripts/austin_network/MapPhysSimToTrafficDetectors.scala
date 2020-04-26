package beam.utils.scripts.austin_network


import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object MapPhysSimToTrafficDetectors {

  def main(args: Array[String]): Unit = {

    import AustinUtils._
    val log=new Logging()
    log.info("start")
    val splitSizeInMeters = 10
    val trafficDetectors = VisualizeVolumeStations.getCoordinatesTrafficCountsFile("E:\\work\\austin\\Traffic_Detectors.csv", 0, 1)
    val physsimNetwork = getPhysSimNetwork("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-25_07-21-44_tmc\\output_network.xml.gz")
    val outputFileName="E:\\work\\austin\\physSimNetworkLinkIdsWithTrafficDetectors.csv"

    log.info("data read")
    val physSimDataPoints = physsimNetwork.getLinks.values().asScala.toVector.flatMap { link =>
      DataVector(DataId(link.toString), link.getFromNode.getCoord, link.getToNode.getCoord, false).produceSpeedDataPointFromSpeedVector(splitSizeInMeters)
    }

    log.info("physSimDataPoints complete")
    val quadTree = getQuadTree(physSimDataPoints)
    log.info("quadTree complete")

    val trafficDataPoints = trafficDetectors.map { case (id, coord) =>
      DataPoint(id, getGeoUtils.wgs2Utm(coord), ArrayBuffer.empty)
    }

    assignDataPointsToQuadTree(quadTree, trafficDataPoints)
    log.info("assignDataPointsToQuadTree complete")

    val links=physSimDataPoints.filter(dataPoint => dataPoint.closestAttractedDataPoint.nonEmpty).map(dataPoint => physsimNetwork.getLinks.get(dataPoint.id.getLinkId))
    val linkIds=AustinUtils.getBothDirectionsOfSelectedLinks(links).map(_.getId.toString).toVector


    writeFile(linkIds,outputFileName,Some("linkId"))
  }

}
