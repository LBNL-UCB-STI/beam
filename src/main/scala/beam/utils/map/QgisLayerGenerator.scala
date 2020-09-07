package beam.utils.map

import java.util

import beam.sim.common.{GeoUtils, SimpleGeoUtils}
import beam.utils.FileUtils.using
import beam.utils.csv.GenericCsvReader
import beam.utils.shape.{Attributes, ShapeWriter}
import com.vividsolutions.jts.geom.{GeometryFactory, LineString}
import org.matsim.api.core.v01.Coord

/**
  *
  * @author Dmitry Openkov
  *
  * read the network and writes it as qgis layer
  */
object QgisLayerGenerator extends App {

  if (args.length != 2) {
    println("Usage: QgisLayerGenerator path/to/cbd_network.csv.gz path/to/write/qgis-layer")
    System.exit(1)
  }

  private val geometryFactory: GeometryFactory = new GeometryFactory()
  val links = readBeamLinks(args(0))
  writeLinkData(args(1), links)

  private def writeLinkData(fileName: String, links: List[NetworkLink]): Unit = {
    val geoUtils = SimpleGeoUtils("epsg:32118")
    using(ShapeWriter.worldGeodetic[LineString, LinkAttributes](fileName))(_.write()) { writer =>
      for {
        entry <- links
      } {
        val from = GeoUtils.toJtsCoordinate(geoUtils.utm2Wgs(entry.from))
        val to = GeoUtils.toJtsCoordinate(geoUtils.utm2Wgs(entry.to))
        val ls = geometryFactory.createLineString(Array(from, to))
        writer.add(
          ls,
          entry.linkId,
          LinkAttributes(
            entry.linkLength,
            entry.linkFreeSpeed,
            entry.linkCapacity,
            entry.numberOfLanes,
            entry.linkModes,
          )
        )
      }
    }
  }

  case class NetworkLink(
    linkId: String,
    linkLength: String,
    linkFreeSpeed: String,
    linkCapacity: String,
    numberOfLanes: String,
    linkModes: String,
    from: Coord,
    to: Coord,
  )

  case class LinkAttributes(
    length: String,
    freeSpeed: String,
    capacity: String,
    numOfLanes: String,
    modes: String,
  ) extends Attributes

  private def toNetLink(mapper: util.Map[String, String]): NetworkLink = {
    val from: Coord = new Coord(mapper.get("fromLocationX").toDouble, mapper.get("fromLocationY").toDouble)
    val to: Coord = new Coord(mapper.get("toLocationX").toDouble, mapper.get("toLocationY").toDouble)
    NetworkLink(
      mapper.get("linkId"),
      mapper.get("linkLength"),
      mapper.get("linkFreeSpeed"),
      mapper.get("linkCapacity"),
      mapper.get("numberOfLanes"),
      mapper.get("linkModes"),
      from,
      to,
    )
  }
  private def readBeamLinks(path: String): List[NetworkLink] = {
    val (rdr, toClose) = GenericCsvReader.readAs[NetworkLink](path, toNetLink, _ => true)
    val list = rdr.toList
    toClose.close()
    println(list.size)
    list
  }
}
