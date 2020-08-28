package beam.utils.analysis

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import beam.sim.common.GeoUtils
import beam.utils.csv.{CsvWriter, GenericCsvReader}
import beam.utils.shape.{Attributes, NoAttributeShapeWriter, ShapeWriter}
import beam.utils.traveltime.NetworkUtil
import beam.utils.traveltime.NetworkUtil.Direction
import beam.utils.{ProfilingUtils, Statistics}
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.vividsolutions.jts.densify.Densifier
import com.vividsolutions.jts.geom._
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

private case class LinkAttributes(nodeId: String, linkId: String) extends Attributes
private case class TrafficAttributes(linkId: String) extends Attributes
private case class MappingAttributes(beamLink: String, trafLink: String, distance: Double) extends Attributes

private case class TranscomLink(id: String, linkPoints: Seq[Coordinate])

// https://github.com/LBNL-UCB-STI/beam/issues/2859
object NewYorkTrafficSpeedAnalysis {

  val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:32118"
  }
  val geometryFactory: GeometryFactory = new GeometryFactory()

  val shouldCreateNetworkShape: Boolean = false
  val shouldCreateTrafficShape: Boolean = false

  // An example of input: 11/24/2018 05:48:37 AM
  val dateTimeFormatter1: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm:ss a")

  // An example of input: 2020-03-25 08:53:03
  val dateTimeFormatter2: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  val firstDate: LocalDateTime = LocalDateTime.of(2020, 3, 1, 0, 0)

  private def trafficFilter(map: Map[String, String]): Boolean = {
    val dataAsOfStr = map("DATA_AS_OF")
    val dataAsOf = Try(LocalDateTime.parse(dataAsOfStr, dateTimeFormatter1)).recover {
      case _: Exception =>
        LocalDateTime.parse(dataAsOfStr, dateTimeFormatter2)
    }.get
    dataAsOf.isAfter(firstDate)
  }

  def main(args: Array[String]): Unit = {
    val pathToCsv = "D:/Work/beam/NewYork/data_sources/DOT_Traffic_Speeds_NBE_Starting_20200301.csv.gz"
    val pathToAlreadyAggregatedCsv = "D:/Work/beam/NewYork/data_sources/only_link_ids.csv"
    val pathToNetwork = "C:/repos/beam/test/input/newyork/r5-prod/physsim-network.xml"

    val transcomLinksWithCorruptedData = readTranscomLinks(pathToAlreadyAggregatedCsv)

    //  writeTranscomLinks(transcomLinksWithCorruptedData)

    val diffs = transcomLinksWithCorruptedData
      .map(_.linkPoints)
      .flatMap { coords =>
        coords.sliding(2, 1).flatMap { xs =>
          if (xs.isEmpty || xs.length == 1)
            None
          else {
            //              val diff = geoUtils.distLatLon2Meters(new Coord(xs(0).x, xs(0).y), new Coord(xs(1).x, xs(1).y))
            val diff = GeoUtils.distFormula(new Coord(xs(0).x, xs(0).y), new Coord(xs(1).x, xs(1).y))
            Some(diff)
          }
        }
      }
      .toVector
    val stats = Statistics(diffs)

    val diffThreshold = 0.01
    println(s"stats: $stats, selected threshold: ${diffThreshold}")
    val transcomLinksOriginal = transcomLinksWithCorruptedData.map { transcomLink =>
      val withinThreshold = getLinksWithinThreshold(transcomLink.linkPoints, diffThreshold)
      transcomLink.copy(linkPoints = withinThreshold)
    }

    val splitThreshold: Double = 50.0
    val transcomLinks = transcomLinksOriginal.flatMap { transcomLink =>
      try {
        val densifiedLineStrings =
          splitLineStringBySegments(geometryFactory.createLineString(transcomLink.linkPoints.toArray), splitThreshold)
        densifiedLineStrings.map { lineString =>
          TranscomLink(transcomLink.id, lineString.getCoordinates)
        }
      } catch {
        case NonFatal(ex) =>
          println(ex)
          throw ex
      }
    }

    println(s"transcomLinksOriginal: ${transcomLinksOriginal.length}")
    println(s"transcomLinks: ${transcomLinks.length}")

    val r5Network = KryoNetworkSerializer.read(new File("C:/repos/beam/test/input/newyork/r5-prod/network.dat"))

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

//    writeOriginAndDestinationFromPathTraversal("C:/Users/User/Downloads/pte_from_walkers.csv")

    val envelope = new Envelope()
    network.getLinks.values().asScala.foreach { link =>
      val (start, end) = getFromToCoordsAsWgs(link)
      envelope.expandToInclude(start.getX, start.getY)
      envelope.expandToInclude(end.getX, end.getY)
    }

    println(s"network size: ${network.getLinks.size()}")
    val quadTree = new QuadTree[Link](envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY)

    network.getLinks.values().asScala.foreach { link =>
      val (start, end) = getFromToCoordsAsWgs(link)
      quadTree.put(start.getX, start.getY, link)
      quadTree.put(end.getX, end.getY, link)
    }
    println(s"quadTree: ${quadTree.size()}")

    val closestBeamLinks = findClosestBeamLinks(transcomLinks, quadTree, Some(5))
    val allBeamLinks = closestBeamLinks.flatMap(_._2)

    val allTranscomLinks = transcomLinks.map { x =>
      val lineString = geometryFactory.createLineString(x.linkPoints.toArray)
      (x, lineString)
    }

    val allBeamLineStrings = allBeamLinks.map { link =>
      val edge = r5Network.streetLayer.edgeStore.getCursor(link.getId.toString.toInt)
      (link, edge.getGeometry)
    }

    val topN: Int = 1
    val level: Int = 1
    println(s"topN: $topN, level: $level")
    println(s"allTranscomLinks: ${allTranscomLinks.length}")
    println(s"allBeamLineStrings: ${allBeamLineStrings.length}")

    val foundLinks = allTranscomLinks.map {
      case (transcomLink, lineString) =>
        val sorted = allBeamLineStrings
          .map {
            case (link, beamLineString) =>
              val d = beamLineString.distance(lineString)
              (link, beamLineString, d)
          }
          .sortBy(x => x._3)
          .take(topN)
        (transcomLink, lineString, sorted)
    }

    val transcomShapeWriter =
      ShapeWriter.worldGeodetic[LineString, MappingAttributes](s"transcom_${splitThreshold}_densified.shp")
    allTranscomLinks.zipWithIndex.foreach {
      case ((link, lineString), idx) =>
        transcomShapeWriter.add(lineString, idx.toString, MappingAttributes("", link.id, 0))
    }
    transcomShapeWriter.write()

    val beamShapeWriter = ShapeWriter.worldGeodetic[LineString, MappingAttributes](
      s"beam_split_${splitThreshold}_top_${topN}_level_${level}_closest_densified.shp"
    )
    var id: Int = 0
    val hs: collection.mutable.Set[(String, String)] = collection.mutable.Set.empty

    foundLinks.foreach {
      case (transcomLink, transcomLineString, xs) =>
        xs.foreach {
          case (beamLink, beamLineString, d) =>
            val connected = Array(beamLink) ++ getConnectedLinks(beamLink, level)
            connected.foreach { link =>
              val linkId = link.getId.toString.toInt
              val key = (link.getId.toString, transcomLink.id)
              if (!hs.contains(key)) {
                val edge = r5Network.streetLayer.edgeStore.getCursor(linkId)
                val attrib = MappingAttributes(link.getId.toString, transcomLink.id, d)
                beamShapeWriter.add(edge.getGeometry, id.toString, attrib)
                id += 1
                hs += key
              }
            }
        }
    }
    beamShapeWriter.write()
  }

  def splitLineStringBySegments(lineString: LineString, splitThresholdMeters: Double): Seq[LineString] = {
    // `densify` expects normal coordinates, not geo (WGS), conver them to UTM first
    val utmCoords = lineString.getCoordinates.map { coord =>
      val utmCoord = geoUtils.wgs2Utm(new Coord(coord.x, coord.y))
      new Coordinate(utmCoord.getX, utmCoord.getY)
    }
    val utmLineString = geometryFactory.createLineString(utmCoords)
    // Densify
    val densified = Densifier.densify(utmLineString, splitThresholdMeters)

    // Create multiple LineStrings
    var prev = densified.getCoordinates.head
    var totalLength: Double = 0.0
    var arr: ArrayBuffer[LineString] = ArrayBuffer()
    var currentCoords = ArrayBuffer[Coordinate]()
    densified.getCoordinates.drop(1).zipWithIndex.foreach {
      case (current, idx) =>
        val utmPrev = new Coord(prev.x, prev.y)
        val utmCurrent = new Coord(current.x, current.y)
        val diff = GeoUtils.distFormula(utmPrev, utmCurrent)
        totalLength += diff
        if (totalLength > splitThresholdMeters && currentCoords.length >= 2) {
          val lineString = geometryFactory.createLineString(currentCoords.toArray)
          arr += lineString
          currentCoords.clear()
          totalLength = 0
        }
        val wgsPrev = geoUtils.utm2Wgs(utmPrev)
        currentCoords += new Coordinate(wgsPrev.getX, wgsPrev.getY)
        prev = current
    }
    val wgsPrev = geoUtils.utm2Wgs(new Coord(prev.x, prev.y))
    currentCoords += new Coordinate(wgsPrev.getX, wgsPrev.getY)
    if (currentCoords.nonEmpty) {
      val lineString = geometryFactory.createLineString(currentCoords.toArray)
      arr += lineString
    }
    arr
  }

  def getLinksWithinThreshold(linkPoints: Seq[Coordinate], diffThreshold: Double): Seq[Coordinate] = {
    val withSliding = linkPoints.sliding(2, 1).toVector
    withSliding.zipWithIndex.foldLeft(Vector.empty[Coordinate]) {
      case (acc, (xs, idx)) =>
        if (xs.isEmpty || xs.length == 1)
          acc
        else {
          val diff = GeoUtils.distFormula(new Coord(xs(0).x, xs(0).y), new Coord(xs(1).x, xs(1).y))
          val updatedAcc = if (diff < diffThreshold) {
            if (idx == withSliding.length - 1) {
              acc ++ Vector(xs(0), xs(1))
            } else {
              acc :+ xs(0)
            }
          } else {
            acc
          }
          updatedAcc
        }
    }
  }

  private def readTranscomLinks(pathToCsv: String): Array[TranscomLink] = {
    var readRows: Int = 0
    val (it, toClose) = GenericCsvReader.readAs[Map[String, String]](pathToCsv, x => x.asScala.toMap, trafficFilter)
    try {
      val result = it
        .map { row =>
          readRows += 1
          val linkId = row("LINK_ID")
          val linksPoints = row("LINK_POINTS")
          val coords = strToCoords(linksPoints).map { x =>
            new Coordinate(x.getX, x.getY)
          }
          // Some of the coordinate are corrupted, filter them out
          TranscomLink(linkId, coords)
        }
        .toArray
        .distinct
      println(s"Read rows: $readRows")
      result
    } finally {
      Try(toClose.close())
    }
  }

  private def writeTranscomLinks(transcomLinks: Array[TranscomLink]) = {
    val csvWriter = new CsvWriter(
      "D:/Work/beam/NewYork/data_sources/only_link_ids.csv",
      Array("LINK_ID", "LINK_POINTS", "DATA_AS_OF")
    )
    transcomLinks.foreach { r =>
      val linksStr = r.linkPoints
        .map { point =>
          s"${point.y},${point.x}"
        }
        .mkString(" ")
      val escapedLinksStr = "\"" + linksStr + "\""
      csvWriter.write(r.id, escapedLinksStr, "\"2020-03-25 08:53:03\"")
    }
    csvWriter.close()
  }

  private def findClosestBeamLinks(
    xs: Seq[TranscomLink],
    quadTreeBounds: QuadTree[Link],
    maybeLevel: Option[Int]
  ): Seq[(TranscomLink, Seq[Link])] = {
    xs.map { transcomLink =>
      val beamLinks = transcomLink.linkPoints
        .flatMap { point =>
          val closestLink = quadTreeBounds.getClosest(point.getOrdinate(0), point.getOrdinate(1))
          Array(closestLink) ++ maybeLevel.map(getConnectedLinks(closestLink, _)).getOrElse(Seq.empty)
        }
        .distinct
        .toSeq
      (transcomLink, beamLinks)
    }
  }

  private def getConnectedLinks(link: Link, level: Int): Seq[Link] = {
    (NetworkUtil.getLinks(link, level, Direction.In) ++ NetworkUtil.getLinks(link, level, Direction.Out)).distinct
  }

  private def writeOriginAndDestinationFromPathTraversal(path: String) = {
    val oShapeWriter = NoAttributeShapeWriter.worldGeodetic[Point]("origin.shp")
    val dhapeWriter = NoAttributeShapeWriter.worldGeodetic[Point]("destination.shp")

    val (pteIter, toClose2) =
      GenericCsvReader.readAs[Map[String, String]](path, mapper => mapper.asScala.toMap, _ => true)

    val ptes = try {
      pteIter.toArray
    } finally {
      toClose2.close()
    }

    var www: Int = 0
    ptes.foreach { pte =>
      val wgsStartX = pte("startX").toDouble
      val wgsStartY = pte("startY").toDouble
      val wgsEndX = pte("endX").toDouble
      val wgsEndY = pte("endY").toDouble
      oShapeWriter.add(
        geometryFactory.createPoint(new Coordinate(wgsStartX, wgsStartY)),
        www.toString,
      )

      dhapeWriter.add(
        geometryFactory.createPoint(new Coordinate(wgsEndX, wgsEndY)),
        www.toString,
      )

      www += 1
    }
    oShapeWriter.write()
    dhapeWriter.write()
  }

  def createShapeFromNetwork(network: Network, pathToShapeFile: String): Unit = {
    val networkShapeWriter = ShapeWriter.worldGeodetic[Point, LinkAttributes](pathToShapeFile)
    try {
      network.getLinks.values().asScala.zipWithIndex.foreach {
        case (link, idx) =>
          val (fromWgs, toWgs) = getFromToCoordsAsWgs(link)
          networkShapeWriter.add(
            geometryFactory.createPoint(new Coordinate(fromWgs.getX, fromWgs.getY)),
            s"${idx}_start",
            LinkAttributes(link.getFromNode.getId.toString, link.getId.toString)
          )
          networkShapeWriter.add(
            geometryFactory.createPoint(new Coordinate(toWgs.getX, toWgs.getY)),
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

  def getFromToCoordsAsWgs(link: Link): (Coord, Coord) = {
    val fromCoordUTM = link.getFromNode.getCoord
    val toCoordUTM = link.getToNode.getCoord
    val fromCoordWgs = geoUtils.utm2Wgs(fromCoordUTM)
    val toCoordWgs = geoUtils.utm2Wgs(toCoordUTM)
    (fromCoordWgs, toCoordWgs)
  }
}
