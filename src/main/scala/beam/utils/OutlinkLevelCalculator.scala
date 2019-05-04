package beam.utils
import java.io.{BufferedReader, File, PrintWriter}
import java.util

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2
import org.matsim.core.utils.io.IOUtils

import scala.collection.JavaConverters._
import scala.collection.mutable

class PhysSimEventHandler(
  val links: util.Map[Id[Link], _ <: Link],
  val level: Int,
  val delimiter: String,
  val outputPath: String
) extends BasicEventHandler
    with LazyLogging {
  val linkVehicleCount: mutable.Map[Int, Int] = mutable.Map[Int, Int]()
  val vehicleToEnterTime: mutable.Map[String, Double] = mutable.Map[String, Double]()
  val linkIdToOutLinks: mutable.Map[Link, Array[Int]] = mutable.Map[Link, Array[Int]]()

  links.values().asScala.foreach { link =>
    linkIdToOutLinks.get(link) match {
      case Some(r) => r
      case None =>
        val r = getOutlink(link, level)
        linkIdToOutLinks.put(link, r)
        r
    }
  }

  val maxColumns: Int = linkIdToOutLinks.values.maxBy(_.size).size
  logger.info(s"Build `linkIdToOutLinks` cache. maxColumns: ${maxColumns}")

  val nodeWriter = new PrintWriter(new File(outputPath))

  writeHeader()

  def writeHeader(): Unit = {
    nodeWriter.append("linkId")
    nodeWriter.append(delimiter)
    nodeWriter.append("travelTimeDuration")
    nodeWriter.append(delimiter)
    nodeWriter.append("vehOnRoad")
    nodeWriter.append(delimiter)
    nodeWriter.append("length")
    nodeWriter.append(delimiter)
    nodeWriter.append("right_sum")
    nodeWriter.append(delimiter)

    (1 to maxColumns).foreach { i =>
      nodeWriter.append(s"outLink${i}_vehOnRoad")
      nodeWriter.append(delimiter)
    }
    nodeWriter.append("dummy_column")
    nodeWriter.append(System.lineSeparator())

    nodeWriter.flush()
  }

  def handleEvent(event: Event): Unit = {
    // logger.info(s"$event")
    val attrib = event.getAttributes
    val linkIdStr = Option(attrib.get("link")).map(_.toInt).get
    event.getEventType match {
      case "entered link" | "vehicle enters traffic" | "wait2link" =>
        val enterTime = event.getTime
        val vehicleId = attrib.get("vehicle")
        vehicleToEnterTime.put(vehicleId, enterTime)
        linkVehicleCount.put(linkIdStr, linkVehicleCount.getOrElse(linkIdStr, 0) + 1)
      case "left link" =>
        val vehicleId = attrib.get("vehicle")
        val enterTime = vehicleToEnterTime.get(vehicleId).get
        val leaveTime = event.getTime
        val travelTime = leaveTime - enterTime
        nodeWriter.append(linkIdStr.toString)
        nodeWriter.append(delimiter)

        nodeWriter.append(travelTime.toString)
        nodeWriter.append(delimiter)

        linkVehicleCount.put(linkIdStr, linkVehicleCount.getOrElse(linkIdStr, 0) - 1)
        val numOfVehicleOnTheRoad = linkVehicleCount(linkIdStr)
        nodeWriter.append(numOfVehicleOnTheRoad.toString)
        nodeWriter.append(delimiter)

        val linkId = Id.create(attrib.get("link"), classOf[Link])
        val link: Link = links.get(linkId)
        val length = links.get(linkId).getLength
        nodeWriter.append(length.toString)
        nodeWriter.append(delimiter)

        val outLinks = linkIdToOutLinks(link)
        val sum = outLinks.map(lid => linkVehicleCount.getOrElse(lid, 0)).sum
        nodeWriter.append(sum.toString)
        nodeWriter.append(delimiter)

        (1 to maxColumns).foreach { i =>
          val idx = i - 1
          outLinks.lift(idx) match {
            case Some(lid) =>
              val cnt = linkVehicleCount.getOrElse(lid, 0)
              nodeWriter.append(s"${cnt.toString}")
              nodeWriter.append(delimiter)
            case None =>
              nodeWriter.append("0")
              nodeWriter.append(delimiter)
          }
        }
        nodeWriter.append("dummy")
        nodeWriter.append(System.lineSeparator())

      case _ =>
    }
  }

  def getOutlink(link: Link, level: Int): Array[Int] = {
    val links = getOutlink0(link, level, Array())
    links.filter(x => x.toString != link.getId.toString)
  }

  def getOutlink0(link: Link, level: Int, arr: Array[Int]): Array[Int] = {
    level match {
      case 0 =>
        arr.filter(x => x != link.getId.toString.toInt).distinct
      case _ =>
        val outLink = link.getToNode.getOutLinks.asScala
        val out = arr ++ outLink.keys.map(_.toString.toInt)
        outLink.values.flatMap(getOutlink0(_, level - 1, out)).toArray.distinct
    }
  }
}

object OutlinkLevelCalculator {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)
    val level = args(2).toInt
    val outputFile = args(3)
    val delimiter = args(4)

    val networkLinks = initializeNetworkLinks(pathToNetworkXml)
    val eventsManager = EventsUtils.createEventsManager()
    val eventHander = new PhysSimEventHandler(networkLinks, level, delimiter, outputFile)
    eventsManager.addHandler(eventHander)
    new MatsimEventsReader(eventsManager).readFile(pathToEventXml)
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }

  def eventXmlParser(eventXml: String, networkXml: String, level: Int, outputPath: String, delimiter: String): Unit = {
    val networkLinks = initializeNetworkLinks(networkXml)

    val reader: BufferedReader = IOUtils.getBufferedReader(eventXml)
    val physsimEventElement = scala.xml.XML.load(reader)
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
