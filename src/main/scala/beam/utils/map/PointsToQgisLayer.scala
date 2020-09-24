package beam.utils.map

import java.util

import beam.sim.common.{GeoUtils, SimpleGeoUtils}
import beam.utils.FileUtils.using
import beam.utils.csv.GenericCsvReader
import beam.utils.shape.{Attributes, ShapeWriter}
import com.vividsolutions.jts.geom.{GeometryFactory, Point}
import org.matsim.api.core.v01.Coord

/**
  *
  * @author Dmitry Openkov
  *
  * read geo data and writes it as qgis layer with circles which sizes correspond to data value
  * geo data is csv: startX, startY, entries
  */
object PointsToQgisLayer extends App {

  if (args.length != 2) {
    println("Usage: QgisLayerGenerator path/to/entries.csv.gz path/to/write/qgis-layer")
    System.exit(1)
  }

  private val geometryFactory: GeometryFactory = new GeometryFactory()
  private val links = readData(args(0))
  writeLinkData(args(1), links)

  private def writeLinkData(fileName: String, links: List[DataEntry]): Unit = {
    val geoUtils = SimpleGeoUtils("epsg:32118")
    using(ShapeWriter.worldGeodetic[Point, LinkAttributes](fileName))(_.write()) { writer =>
      for {
        (entry, idx) <- links.zipWithIndex
      } {
        val coord = GeoUtils.toJtsCoordinate(entry.coord)
        val ls = geometryFactory.createPoint(coord)
        writer.add(
          ls,
          (idx + 1).toString,
          LinkAttributes(
            entry.value,
          )
        )
      }
    }
    println(s"Written to: $fileName")
  }

  private case class DataEntry(
    coord: Coord,
    value: Double,
  )

  private case class LinkAttributes(
    value: Double,
  ) extends Attributes

  private def toNetLink(mapper: util.Map[String, String]): DataEntry = {
    val from: Coord = new Coord(mapper.get("startX").toDouble, mapper.get("startY").toDouble)
    DataEntry(
      from,
      mapper.get("relative_error").toDouble,
    )
  }
  private def readData(path: String): List[DataEntry] = {
    val (rdr, toClose) = GenericCsvReader.readAs[DataEntry](path, toNetLink, _ => true)
    val list = rdr.toList
    toClose.close()
    println(list.size)
    list
  }
}
