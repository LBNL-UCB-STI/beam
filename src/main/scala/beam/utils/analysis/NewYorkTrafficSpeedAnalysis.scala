package beam.utils.analysis

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.csv.GenericCsvReader
import beam.utils.shape.{Attributes, ShapeWriter}
import com.vividsolutions.jts.geom.{Coordinate, Envelope, GeometryFactory, Point}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

private case class LinkAttributes(nodeId: String, linkId: String) extends Attributes
private case class TrafficAttributes(linkId: String) extends Attributes

object NewYorkTrafficSpeedAnalysis {

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }
  val geometryFactory: GeometryFactory = new GeometryFactory()

  val shouldCreateNetworkShape: Boolean = false
  val shouldCreateTrafficShape: Boolean = true

  // An example of input: 11/24/2018 05:48:37 AM
  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")

  val firstDate: LocalDateTime = LocalDateTime.of(2020, 3, 1, 0, 0)

  private def trafficFilter(map: Map[String, String]): Boolean = {
    val dataAsOfStr = map("DATA_AS_OF")
    val dataAsOf = LocalDateTime.parse(dataAsOfStr, dateTimeFormatter)
    dataAsOf.isAfter(firstDate)
  }

  def main(args: Array[String]): Unit = {
    val pathToCsv = "D:/Work/beam/NewYork/data_sources/DOT_Traffic_Speeds_NBE.csv.gz"
    val pathToNetwork = "C:/repos/beam/test/input/newyork/r5-prod/physsim-network.xml"

    if (shouldCreateTrafficShape) {
      ProfilingUtils.timed("Create shape file from traffic speeds", x => println(x)) {
        createShapeFromTraffic(pathToCsv, "traffic_new.shp")
      }
    }

    val network = readNetwork(pathToNetwork)
    if (shouldCreateNetworkShape) {
      ProfilingUtils.timed("Create network shape file", x => println(x)) {
        createShapeFromNetwork(network, "network.shp")
      }
    }

    val envelope = new Envelope()
    network.getLinks.values().asScala.foreach { link =>
      val (start, end) = getFromToCoords(link)
      envelope.expandToInclude(start.getX, start.getY)
      envelope.expandToInclude(end.getX, end.getY)
    }

    println(s"network size: ${network.getLinks.size()}")
    val quadTreeBounds = new QuadTree[Link](envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY)

    network.getLinks.values().asScala.foreach { link =>
      val (start, end) = getFromToCoords(link)
      quadTreeBounds.put(start.getX, start.getY, link)
      quadTreeBounds.put(end.getX, end.getY, link)
    }
    println(s"quadTreeBounds: ${quadTreeBounds.size()}")

    val (it, toClose) = GenericCsvReader.readAs[Map[String, String]](pathToCsv, x => x.asScala.toMap, trafficFilter)
    try {
      val r = it.take(1000).toVector

      r.foreach { map =>
        println(s"map: ${map.size}")
        val linkId = map("LINK_ID")
        val linksPoints = map.get("LINK_POINTS").map(strToCoords).getOrElse(Vector.empty)
        println(s"linkId: $linkId")
        linksPoints.foreach { point =>
          val link = quadTreeBounds.getClosest(point.getX, point.getY)
          val (start, end) = getFromToCoords(link)
          val startDiff = geoUtils.distLatLon2Meters(start, point)
          val endDiff = geoUtils.distLatLon2Meters(end, point)
          println(s"Link: ${link}")
          println(s"Found closest. start: ${start}, startDiff: $startDiff, end: $end, endDiff: $endDiff")
        }
        println()
      }

      println(r.length)
    } finally {
      toClose.close()
    }
  }

  def createShapeFromNetwork(network: Network, pathToShapeFile: String): Unit = {
    val networkShapeWriter = ShapeWriter.worldGeodetic[Point, LinkAttributes](pathToShapeFile)
    try {
      network.getLinks.values().asScala.zipWithIndex.foreach {
        case (link, idx) =>
          val (fromUTM, toUTM) = getFromToCoords(link)
          networkShapeWriter.add(
            geometryFactory.createPoint(new Coordinate(fromUTM.getX, fromUTM.getY)),
            s"${idx}_start",
            LinkAttributes(link.getFromNode.getId.toString, link.getId.toString)
          )
          networkShapeWriter.add(
            geometryFactory.createPoint(new Coordinate(toUTM.getX, toUTM.getY)),
            s"${idx}_end",
            LinkAttributes(link.getToNode.getId.toString, link.getId.toString)
          )
      }
    } finally {
      networkShapeWriter.write()
    }
  }

  def createShapeFromTraffic(pathToCsv: String, pathToShapeFile: String): Unit = {
    val shapeWriter = ShapeWriter.worldGeodetic[Point, TrafficAttributes](pathToShapeFile)

    val (it, toClose) = GenericCsvReader.readAs[Map[String, String]](
      pathToCsv,
      x => x.asScala.toMap,
      trafficFilter
    )

    try {
      val r = it.map { row =>
        val linkId = row("LINK_ID")
        val linksPoints = row("LINK_POINTS")
        (linkId, strToCoords(linksPoints))
      }
      var counter: Int = 0
      r.toArray.groupBy { case (linkId, linkPoints) => (linkId, linkPoints) }.foreach {
        case ((linkId, linksPoints), _) =>
          linksPoints.foreach { point =>
            shapeWriter.add(
              geometryFactory.createPoint(new Coordinate(point.getX, point.getY)),
              s"$counter",
              TrafficAttributes(linkId)
            )
            counter += 1
          }
      }
      println(s"Going to write $counter points to traffic shape file")
    } finally {
      Try(toClose.close())
      Try(shapeWriter.write())
    }
  }

  def strToCoords(linksStr: String): Vector[Coord] = {
    try {
      val links = linksStr.split(" ")
      links.flatMap { link =>
        val splitted = link.split(",")
        if (splitted.length == 2) {
          val maybeLat = Try(splitted(0).toDouble).toOption
          val maybeLon = Try(splitted(1).toDouble).toOption

          (maybeLon, maybeLat) match {
            case (Some(lon), Some(lat)) =>
              Some(new Coord(lon, lat))
            case _ =>
              None
          }
          // In reverse order because network.xml contains X as lon, Y as lat
        } else None
      }.toVector
    } catch {
      case NonFatal(ex) =>
        println(ex)
        Vector.empty
    }
  }

  def readNetwork(path: String): Network = {
    val n = NetworkUtils.createNetwork()
    new MatsimNetworkReader(n)
      .readFile(path)
    n
  }

  def getFromToCoords(link: Link): (Coord, Coord) = {
    val fromCoordUTM = link.getFromNode.getCoord
    val toCoordUTM = link.getToNode.getCoord
    val fromCoordWgs = geoUtils.utm2Wgs(fromCoordUTM)
    val toCoordWgs = geoUtils.utm2Wgs(toCoordUTM)
    (fromCoordWgs, toCoordWgs)
  }
}
