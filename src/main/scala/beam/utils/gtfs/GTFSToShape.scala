package beam.utils.gtfs

import java.io.{Closeable, File, IOException, InputStream}
import java.util.zip.ZipFile

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

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

  def writeShapeFile(zipFolderPath: String, outputPath: String): Unit = {

    def getCoords(zipFile: ZipFile): Set[WgsCoordinate] = {
      val entry = zipFile.getEntry("stops.txt")
      if (entry == null) {
        Set.empty[WgsCoordinate]
      } else {
        val stream = zipFile.getInputStream(entry);
        readWgsCoordinates(stream)
      }
    }
    val gtfsFolder = new File(zipFolderPath);
    val gtfsFiles = gtfsFolder.listFiles();

    val coords = scala.collection.mutable.ListBuffer.empty[Set[WgsCoordinate]]

    for (zipFilePath <- gtfsFiles.filter(file => file.isFile && file.getName.endsWith(".zip"))) {
      val zipFile = new ZipFile(zipFilePath)

      try {
        val gtfsCoords = getCoords(zipFile)
        logger.info(s"got ${gtfsCoords.size} coordinates from $zipFilePath")
        coords += gtfsCoords
      } catch {
        case ioexc: IOException => logger.error(s"IOException happened: $ioexc")
      } finally {
        zipFile.close()
      }
    }

    val allCoords = coords.flatten
    createShapeFile(allCoords, outputPath)
    logger.info(s"${allCoords.size} coordinates written into shape file")
  }

  def main(args: Array[String]): Unit = {
    val srcFile = if (args.length > 0) args(0) else "" //"/mnt/data/work/beam/beam-new-york/test/input/newyork/r5-latest"
    val outFile = if (args.length > 1) args(1) else "" //"/mnt/data/work/beam/gtfs-NY.shp"

    logger.info(s"gtfs zip folder: '$srcFile'")
    logger.info(s"stops coordinate will be in shape file: '$outFile'")

    val f = new File(srcFile);
    if (f.exists && f.isDirectory) {
      writeShapeFile(srcFile, outFile)
    } else {
      if (!f.exists) {
        logger.error(s"given path to gtfs zip archives does not exist")
      } else {
        logger.error(s"given path to gtfs zip archives is not a folder")
      }
    }

  }
}
