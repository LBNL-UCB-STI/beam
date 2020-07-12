package beam.utils.gtfs

import java.io.{File, IOException}
import java.util.zip.ZipFile

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.gtfs.Model._
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._
import scala.collection.mutable

object GTFSToShape extends LazyLogging {

  def coordsToShapefile(coords: Traversable[WgsCoordinate], shapeFileOutputPath: String): Unit = {
    val features = mutable.ArrayBuffer[SimpleFeature]()
    val pointf: PointFeatureFactory = new PointFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .create()
    coords.foreach { wsgCoord =>
      val coord = new com.vividsolutions.jts.geom.Coordinate(wsgCoord.longitude, wsgCoord.latitude)
      val feature = pointf.createPoint(coord)
      features += feature
    }
    logger.info(s"Creating of $shapeFileOutputPath shape file with ${features.size} features")
    ShapeFileWriter.writeGeometries(features.asJava, shapeFileOutputPath)
  }

  def gtfsToShapefiles(zipFolderPath: String, outputPath: String): Unit = {
    val gtfsFolder = new File(zipFolderPath);
    val gtfsFiles = gtfsFolder.listFiles();

    val routeTypeToCoords = mutable.HashMap.empty[String, mutable.Buffer[WgsCoordinate]]
    val routeTypeToRoutes = mutable.HashMap.empty[String, mutable.Buffer[Route]]

    for (sourceFile <- gtfsFiles.filter(file => file.isFile && file.getName.endsWith(".zip"))) {
      val zipFile = new ZipFile(sourceFile)
      val zipFileName = sourceFile.getName
      logger.info(s"processing $zipFileName")

      // routes.txt     : route_id -> route_type, route_long_name
      // trips.txt      : route_id -> trip_id
      // stop_times.txt : trip_id -> stop_id
      // stops.txt      : stop_id -> stop_lan, stop_lon

      try {
        val routes = GTFSReader.readRoutes(zipFile, zipFileName)
        val trips = GTFSReader.readTrips(zipFile)
        val stopTimes = GTFSReader.readStopTimes(zipFile)
        val stops = GTFSReader.readStops(zipFile)

        // write a shapefile for current GTFS data source
        coordsToShapefile(stops.map(s => s.wgsPoint), s"$outputPath/$zipFileName.shp")

        // group all data by route type
        routes.foreach{ route =>
          routeTypeToRoutes.get(route.routeType) match {
            case Some(rts) => rts += route
            case None => routeTypeToRoutes(route.routeType) = mutable.Buffer(route)
          }
        }

        val routeIdToRoute = routes.map(r => r.id         -> r).toMap
        val tripIdToRoute = trips.map(t => t.tripId       -> routeIdToRoute.get(t.routeId)).toMap
        val stopIdToRoute = stopTimes.map(st => st.stopId -> tripIdToRoute.get(st.tripId).flatten).toMap
        val stopToRoute = stops.map(s => (s, stopIdToRoute.get(s.id).flatten))
        stopToRoute.foreach {
          case (stop, Some(route)) =>
            routeTypeToCoords.get(route.routeType) match {
              case Some(coords) => coords += stop.wgsPoint
              case None         => routeTypeToCoords(route.routeType) = mutable.Buffer(stop.wgsPoint)
            }
          case (stop, None) => logger.error(s"Stop ID:${stop.id} lat:${stop.lat} lon:${stop.lon} does not have a route.")
        }

      } catch {
        case ioexc: IOException => logger.error(s"IOException happened: $ioexc")
      } finally {
        zipFile.close()
      }
    }

    routeTypeToCoords.foreach{ case (routeType, coords) =>
      coordsToShapefile(coords, s"$outputPath/routeType.$routeType.stops.shp")
    }

  }

  def main(args: Array[String]): Unit = {
    val srcDir = if (args.length > 0) args(0) else "" // "/mnt/data/work/beam/beam-new-york/test/input/newyork/r5-latest"
    val outDir = if (args.length > 1) args(1) else "" // "/mnt/data/work/beam/gtfs-NY"

    logger.info(s"gtfs zip sources folder: '$srcDir'")
    logger.info(s"shapefiles output folder: '$outDir'")

    val od = new File(outDir)
    val outputIsReady = {
      if (od.exists) {
        if (od.isDirectory) {
          true
        } else {
          logger.error(s"The output path $outDir is not a folder.")
          false
        }
      } else {
        if (od.mkdir) {
          true
        } else {
          logger.error(s"An attempt to create the folder $outDir failed.")
          false
        }
      }
    }

    val f = new File(srcDir);
    val inputLooksFine = {
      if (f.exists) {
        if (f.isDirectory) {
          true
        } else {
          logger.error(s"Given path to gtfs zip archives is not a folder.")
          false
        }
      } else {
        logger.error(s"Given path to gtfs zip archives does not exist.")
        false
      }
    }

    if (inputLooksFine && outputIsReady) {
      gtfsToShapefiles(srcDir, outDir)
    }
  }
}
