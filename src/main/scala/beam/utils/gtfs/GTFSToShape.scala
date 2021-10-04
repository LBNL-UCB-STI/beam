package beam.utils.gtfs

import java.io.{File, IOException}
import java.util.zip.ZipFile

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.csv.CsvWriter
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
    val gtfsFolder = new File(zipFolderPath)
    val gtfsFiles = gtfsFolder.listFiles()

    val agencyToStops = mutable.HashMap.empty[Agency, mutable.Buffer[WgsCoordinate]]
    val sourceToStops = mutable.HashMap.empty[String, Int]

    val sourceToOrphanStops = mutable.HashMap.empty[String, mutable.Buffer[WgsCoordinate]]
    def takeIntoAccauntAnOrphaneStop(stop: Stop, source: String): Unit = {
      sourceToOrphanStops.get(source) match {
        case Some(orphanStops) => orphanStops += stop.wgsPoint
        case None              => sourceToOrphanStops(source) = mutable.Buffer(stop.wgsPoint)
      }
    }

    for (sourceFile <- gtfsFiles.filter(file => file.isFile && file.getName.endsWith(".zip"))) {
      val zipFile = new ZipFile(sourceFile)
      val zipFileName = sourceFile.getName
      logger.info(s"processing $zipFileName")

      try {
        val agencies = GTFSReader.readAgencies(zipFile, zipFileName)
        val defaultAgency = if (agencies.size == 1) agencies.headOption else None
        val defaultAgemcyId = defaultAgency.map(a => a.id).getOrElse("")

        val routes = GTFSReader.readRoutes(zipFile, zipFileName, defaultAgemcyId)
        val trips = GTFSReader.readTrips(zipFile)
        val stopTimes = GTFSReader.readStopTimes(zipFile)
        val stops = GTFSReader.readStops(zipFile)

        sourceToStops(zipFileName) = stops.size

        // write a shapefile for current GTFS data source
        coordsToShapefile(stops.map(s => s.wgsPoint), s"$outputPath/source.$zipFileName.shp")

        agencies.foreach { agency =>
          if (!agencyToStops.contains(agency)) {
            agencyToStops(agency) = mutable.Buffer.empty[WgsCoordinate]
          }
        }

        val agencyIdToAgency = agencies.map(a => a.id -> a).toMap
        val routeIdToRoute = routes.map(r => r.id -> r).toMap
        val tripIdToRoute = trips
          .map(t => {
            val route = routeIdToRoute.get(t.routeId)
            if (route.isEmpty) logger.warn(s"trip id:${t.tripId} can't find route by routeId:${t.routeId}.")
            t.tripId -> route
          })
          .toMap
        val stopIdToRoute = stopTimes
          .map(st => {
            val route = tripIdToRoute.get(st.tripId).flatten
            if (route.isEmpty) logger.warn(s"stopTimes stopId:${st.stopId} can't find route by tripId:${st.tripId}")
            st.stopId -> route
          })
          .toMap

        val stopRouteAgency = stops.map(stop => {
          val routeOpt: Option[Route] = stopIdToRoute.get(stop.id).flatten
          val agencyOpt: Option[Agency] = routeOpt match {
            case Some(r) => agencyIdToAgency.get(r.agencyId)
            case None    => None
          }
          if (routeOpt.isEmpty || agencyOpt.isEmpty)
            logger.warn(s"stop id:${stop.id} route:${routeOpt.toString} agency:${agencyOpt.toString}")

          (stop, routeOpt, agencyOpt)
        })

        stopRouteAgency.foreach {
          case (stop, _, Some(agency))                => agencyToStops(agency) += stop.wgsPoint
          case (stop, _, _) if defaultAgency.nonEmpty => agencyToStops(defaultAgency.get) += stop.wgsPoint
          case (stop, Some(route), _)                 => takeIntoAccauntAnOrphaneStop(stop, route.source)
          case (stop, None, None)                     => takeIntoAccauntAnOrphaneStop(stop, zipFileName)
        }

      } catch {
        case ioexc: IOException => logger.error(s"IOException happened: $ioexc")
      } finally {
        zipFile.close()
      }
    }

    agencyToStops.groupBy { case (agency, _) => agency.name }.foreach { case (agencyName, grouped) =>
      val coordinates = grouped.values.flatten
      if (coordinates.nonEmpty) {
        coordsToShapefile(coordinates, s"$outputPath/agency.$agencyName.stops.shp")
      }
    }

    sourceToOrphanStops.foreach { case (source, coordinates) =>
      logger.warn(s"There are ${coordinates.length} stops without route or agency from $source gtfs source.")
      coordsToShapefile(coordinates, s"$outputPath/orphan.$source.stops.shp")
    }

    val agencyCswWriter = new CsvWriter(
      s"$outputPath/agencies.csv",
      Vector("id", "name", "url", "# of stops", "source", "# of stops in source")
    )

    agencyToStops.foreach { case (agency, stops) =>
      agencyCswWriter.writeRow(
        IndexedSeq(agency.id, agency.name, agency.url, stops.length, agency.source, sourceToStops(agency.source))
      )
    }

    sourceToOrphanStops.foreach { case (source, stops) =>
      agencyCswWriter.writeRow(IndexedSeq("", "", "", stops.length, source, ""))
    }

    agencyCswWriter.close()

  }

  def main(args: Array[String]): Unit = {
    val srcDir =
      if (args.length > 0) args(0) else "" // "/mnt/data/work/beam/beam-new-york/test/input/newyork/r5-latest"
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

    val f = new File(srcDir)
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
