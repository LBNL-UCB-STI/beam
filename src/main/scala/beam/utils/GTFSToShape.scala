package beam.utils

import java.io.{Closeable, File, IOException, InputStream}
import java.util.zip.ZipFile

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object GTFSToShape extends LazyLogging {

  def readWgsCoordinates(stream: InputStream): Set[WgsCoordinate] = {
    val (iter: Iterator[WgsCoordinate], toClose: Closeable) =
      GenericCsvReader.readFromStreamAs[WgsCoordinate](stream, toWgsCoordinate, _ => true)
    try {
      iter.toSet
    } finally {
      toClose.close()
    }
  }

  def toWgsCoordinate(rec: java.util.Map[String, String]): WgsCoordinate = {
    WgsCoordinate(
      latitude = rec.get("stop_lat").toDouble,
      longitude = rec.get("stop_lon").toDouble
    )
  }

  def createShapeFile(coords: Traversable[WgsCoordinate], shapeFileOutputPath: String): Unit = {
    val features = ArrayBuffer[SimpleFeature]()
    val pointf: PointFeatureFactory = new PointFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .create()
    coords.foreach { wsgCoord =>
      val coord = new com.vividsolutions.jts.geom.Coordinate(wsgCoord.longitude, wsgCoord.latitude)
      val feature = pointf.createPoint(coord)
      features += feature
    }
    ShapeFileWriter.writeGeometries(features.asJava, shapeFileOutputPath)
  }

  def writeShapeFile(zipFilePath: String, outputPath: String): Unit = {
    val zipFile = new ZipFile(zipFilePath);

    try {
      val entry = zipFile.getEntry("stops.txt")
      val stream = zipFile.getInputStream(entry);
      val coords = readWgsCoordinates(stream)
      createShapeFile(coords, outputPath)
    } catch {
      case ioexc: IOException => logger.error(s"IOException happened: $ioexc")
    } finally {
      zipFile.close()
    }
  }

  def main(args: Array[String]): Unit = {
    val srcFile = if (args.length > 1) args(1) else "/mnt/data/work/beam/gtfs-detroit.zip"
    val outFile = if (args.length > 2) args(2) else "/mnt/data/work/beam/gtfs-detroit.shp"

    val f = new File(srcFile);
    if (f.exists && !f.isDirectory) {
      logger.info(s"gtfs zip archive will be read from: '$srcFile'")
      logger.info(s"stops coordinate will be in shape file: '$outFile'")
      writeShapeFile(srcFile, outFile)
    } else {
      if (!f.exists) {
        logger.error(s"given path to gtfs zip archive does not exist")
      } else {
        logger.error(s"given path to gtfs zip archive is a folder not an archive")
      }
    }

  }
}
