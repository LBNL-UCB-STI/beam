package beam.utils
import java.io.{File, PrintWriter}
import java.util

import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2
import org.matsim.api.core.v01.Id

import scala.collection.mutable
import scala.collection.JavaConverters._

object OutlinkLevelCalculator {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)
    val level = args(2).toInt
    val outputFile = args(3)
    val delimiter = args(4)
    eventXmlParser(pathToEventXml, pathToNetworkXml, level, outputFile, delimiter)
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }

  def eventXmlParser(eventXml: String, networkXml: String, level: Int, outputPath: String, delimiter: String): Unit = {

    val networkLinks = initializeNetworkLinks(networkXml)

    val physsimEventElement = scala.xml.XML.loadFile(eventXml)
    val linkVehicleCount: mutable.Map[Int, Int] = mutable.Map[Int, Int]()
    (physsimEventElement \ "event").foreach { event =>
      val link = (event \ "@link").text.toInt
      (event \ "@type").text match {
        case "entered link" => linkVehicleCount.put(link, linkVehicleCount.getOrElse(link, 0) + 1)
        case "wait2link"    => linkVehicleCount.put(link, linkVehicleCount.getOrElse(link, 0) + 1)
        case "left link"    => linkVehicleCount.put(link, linkVehicleCount.getOrElse(link, 0) - 1)

        case _ =>
      }
    }

    val nodeWriter = new PrintWriter(new File(outputPath))

    val rowOutLink = linkVehicleCount map { linkCount =>
      val link = linkCount._1
      val count = linkCount._2
      val linkDetails = networkLinks.get(Id.create(link, classOf[Link]))
      val row = new StringBuffer()
        .append(link)
        .append(delimiter)
        .append(linkDetails.getLength / linkDetails.getFreespeed)
        .append(delimiter)
        .append(count)
        .append(delimiter)
        .append(linkDetails.getLength)

      (row.toString, getOutlink(linkDetails, level, Set()))
    }

    val maxCount = rowOutLink.values.map(_.size).max
    val headerItem = new StringBuffer()
      .append("linkId")
      .append(delimiter)
      .append("travelTimeDuration")
      .append(delimiter)
      .append("vehOnRoad")
      .append(delimiter)
      .append("length")

    for (i <- 1 to maxCount) {
      headerItem.append(delimiter).append("outLink" + i + "_vehOnRoad")
    }

    headerItem.append("\n")
    nodeWriter.write(headerItem.toString)

    rowOutLink foreach {
      case (first, second) =>
        val requiredDelimeter = maxCount - second.size
        val row = new StringBuffer()
        row.append(first)
        second.foreach(rowItem => row.append(delimiter).append(linkVehicleCount.getOrElse(rowItem, 0)))
        for (i <- 0 until requiredDelimeter) {
          row.append(delimiter)
        }
        row.append("\n")
        nodeWriter.write(row.toString)
    }

    nodeWriter.close()
  }

  def getOutlink(link: Link, level: Int, list: Set[Int]): Set[Int] = {
    level match {
      case 0 =>
        list
      case _ =>
        val outLink = link.getToNode.getOutLinks.asScala
        val out = list ++ outLink.keys.map(_.toString.toInt)
        outLink.values.flatMap(getOutlink(_, level - 1, out)).toSet
    }
  }
}
