package scripts.transit

import beam.utils.FileUtils
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.StrictLogging
import org.onebusaway.gtfs.model.{Route, Stop}

import java.nio.file.{Path, Paths}
import scala.collection.JavaConverters._

/**
  * @author Dmitry Openkov
  *
  * read r5 data and save the route to stop mapping to csv
  */
object GtfsExtractor extends App with StrictLogging {

  if (args.length != 3) {
    println("Usage: GtfsExtractor path/to/r5_dir path/to/route_to_stop.csv, comma_separated_list_of_GTFS_zip_files")
  } else {
    val source = Paths.get(args(0))
    val output = Paths.get(args(1))
    val gtfsFiles = args(2).split(",")

    logger.info("Transforming {} files", gtfsFiles.length)
    logger.info("Transform to {}", output)

    val routeToStops: List[Entry] = gtfsFiles
      .map(gtfsFileName => gtfsFileName.split(".zip").head -> source.resolve(gtfsFileName))
      .map { case (name, path) =>
        extractStops(name, path)
      }
      .reduce(_ ++ _)

    saveToCSV(routeToStops, output)
  }

  private case class Entry(fileName: String, route: Route, stops: Seq[Stop])

  private def extractStops(fileName: String, gtfsPath: Path): List[Entry] = {
    val dao = GtfsUtils.createDAO(gtfsPath)
    val routeToStop = dao.getAllTrips.asScala
      .groupBy(_.getRoute)
      .mapValues(_.head)
      .mapValues(trip => dao.getStopTimesForTrip(trip).asScala)
      .mapValues(stopTimes => stopTimes.map(_.getStop))

    routeToStop.map { case (route, stops) =>
      Entry(fileName, route, stops)
    }.toList
  }

  private def saveToCSV(routeToStops: List[Entry], outFile: Path) = {
    val csvWriter: CsvWriter =
      new CsvWriter(
        outFile.toString,
        Vector("file_name", "agency", "route", "route_short_name", "stop", "lat", "lon")
      )
    FileUtils.using(csvWriter) { writer =>
      for {
        e    <- routeToStops
        stop <- e.stops
      } yield writer.write(
        e.fileName,
        e.route.getAgency.getId,
        e.route.getId.getId,
        Option(e.route.getShortName),
        stop.getId.getId,
        stop.getLat,
        stop.getLon
      )
    }
  }
}
