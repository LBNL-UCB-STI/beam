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
    val pathToOutputFile = args(2)
    val level = args(4).toInt
    val delimiter = args(4)
    eventXmlParser(pathToEventXml, pathToNetworkXml, level, pathToOutputFile, delimiter)
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }

  def eventXmlParser(eventXml: String, networkXml: String, level: Int, outputPath: String, delimeter: String): Unit = {

    val networkLinks = initializeNetworkLinks(networkXml)

    val physsimEventElement = scala.xml.XML.loadFile(eventXml)
    val linkVehicleCount: mutable.Map[String, Int] = mutable.Map[String, Int]()
    (physsimEventElement \ "event").foreach { event =>
      val link = (event \ "@link").text
      (event \ "@type").text match {
        case "departure" => linkVehicleCount.put(link, linkVehicleCount.getOrElse(link, 0) + 1)
        case "arrival"   => linkVehicleCount.put(link, linkVehicleCount.getOrElse(link, 0) - 1)
        case _           =>
      }
    }

    val nodeWriter = new PrintWriter(new File(outputPath))
    nodeWriter.write("linkId" + delimeter + "travelTimeDuration" + delimeter + "vehOnRoad" + delimeter + "length\n")
    linkVehicleCount foreach {
      case (link, count) =>
        val linkDetails = networkLinks.get(Id.create(link, classOf[Link]))
        val row = new StringBuffer()
          .append(link)
          .append(delimeter)
          .append(linkDetails.getLength / linkDetails.getFreespeed)
          .append(delimeter)
          .append(count)
          .append(delimeter)
          .append(linkDetails.getLength)
          .append("\n")

        val outLink = getOutlink(linkDetails, level, Set())

        println(
          linkDetails.getId + "-->" + linkVehicleCount(linkDetails.getId.toString) + " -> " + outLink
            .map(id => (id, linkVehicleCount.getOrElse(id.toString, 0)))
            .toMap
        )

        nodeWriter.write(row.toString)
    }
    nodeWriter.close()
  }

  def getOutlink(link: Link, level: Int, list: Set[String]): Set[String] = {
    level match {
      case 0 =>
        list
      case _ =>
        val outLink = link.getToNode.getOutLinks.asScala
        val out = list ++ outLink.keys.map(_.toString)
        outLink.values.flatMap(getOutlink(_, level - 1, out)).toSet
    }
  }
}
