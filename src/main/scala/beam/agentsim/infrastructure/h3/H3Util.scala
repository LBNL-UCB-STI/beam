package beam.agentsim.infrastructure.h3

import scala.collection.JavaConverters._
import scala.collection.mutable

import com.typesafe.scalalogging.LazyLogging
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{GeometryFactory, Polygon}
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}

object H3Util extends LazyLogging {

  def writeToShapeFile(filename: String, content: H3Content): Unit = {
    if (content.numberOfPoints == 0) {
      logger.warn("Content is empty and file was not generated")
    } else {
      val gf = new GeometryFactory()
      val hexagons: Iterable[(Polygon, String, Int, Int)] = content.asBuckets.map { bucket =>
        val boundary: mutable.Seq[GeoCoord] = H3Wrapper.h3Core.h3ToGeoBoundary(bucket.index.value).asScala
        val polygon: Polygon = gf.createPolygon(boundary.map(toJtsCoordinate).toArray :+ toJtsCoordinate(boundary.head))
        (
          polygon,
          bucket.index.value,
          bucket.points.size,
          bucket.index.resolution
        )
      }
      val pf: PolygonFeatureFactory = new PolygonFeatureFactory.Builder()
        .setCrs(MGC.getCRS("EPSG:4326"))
        .setName("nodes")
        .addAttribute("Index", classOf[String])
        .addAttribute("Size", classOf[java.lang.Integer])
        .addAttribute("Resolution", classOf[java.lang.Integer])
        .create()
      val shpPolygons = hexagons.map {
        case (polygon, indexValue, indexSize, resolution) =>
          pf.createPolygon(
            polygon.getCoordinates,
            Array[Object](indexValue, new Integer(indexSize), new Integer(resolution)),
            null
          )
      }
      ShapeFileWriter.writeGeometries(shpPolygons.asJavaCollection, filename)
    }
  }

  private def toJtsCoordinate(in: GeoCoord): com.vividsolutions.jts.geom.Coordinate = {
    new com.vividsolutions.jts.geom.Coordinate(in.lng, in.lat)
  }

}
