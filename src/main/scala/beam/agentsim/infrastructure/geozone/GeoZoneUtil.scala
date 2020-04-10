package beam.agentsim.infrastructure.geozone

import java.io.{FileInputStream, InputStreamReader}
import java.nio.file.Path

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.typesafe.scalalogging.LazyLogging
import com.uber.h3core.util.GeoCoord
import com.vividsolutions.jts.geom.{GeometryFactory, Polygon}
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PolygonFeatureFactory, ShapeFileWriter}
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

object GeoZoneUtil extends LazyLogging {

  def readWgsCoordinatesFromCsv(relativePath: Path): Set[WgsCoordinate] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[WgsCoordinate]()
    try {
      mapReader = new CsvMapReader(readerFromFile(relativePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val coordX = line.get("longitude").toDouble
        val coordY = line.get("latitude").toDouble
        res.append(WgsCoordinate(coordY, coordX))
        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toSet
  }

  private def readerFromFile(filePath: Path): java.io.Reader = {
    new InputStreamReader(new FileInputStream(filePath.toFile))
  }

  def writeToShapeFile(filename: String, content: GeoZoneSummary): Unit = {
    if (content.items.isEmpty) {
      logger.warn("Content is empty and file was not generated")
    } else {
      val gf = new GeometryFactory()
      val hexagons: Iterable[(Polygon, String, Int, Int)] = content.items.map { bucket =>
        val boundary: mutable.Seq[GeoCoord] = H3Wrapper.h3Core.h3ToGeoBoundary(bucket.index.value).asScala
        val polygon: Polygon = gf.createPolygon(boundary.map(toJtsCoordinate).toArray :+ toJtsCoordinate(boundary.head))
        (
          polygon,
          bucket.index.value,
          bucket.size,
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
