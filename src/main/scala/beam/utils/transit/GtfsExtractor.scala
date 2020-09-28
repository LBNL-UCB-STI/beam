package beam.utils.transit

import java.nio.file.{Path, Paths}

import beam.utils.FileUtils
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.StrictLogging
import org.onebusaway.gtfs.model.{Route, Stop}

import scala.collection.JavaConverters._
import scala.collection.immutable

/**
  *
  * @author Dmitry Openkov
  *
  * read r5 data and save the route to stop mapping to csv
  */
object GtfsExtractor extends App with StrictLogging {

  if (args.length != 2) {
    println("Usage: GtfsExtractor path/to/r5_dir path/to/write/route_to_stop.csv")
    System.exit(1)
  }

  private val agencies = List(
    "MTA_Bronx_20200121",
    "MTA_Brooklyn_20200118",
    "MTA_Manhattan_20200123",
    "MTA_Queens_20200118",
    "MTA_Staten_Island_20200118",
    "NJ_Transit_Bus_20200210",
  )
  private val source = Paths.get(args(0))

  logger.info("Transforming {} files", agencies.size)
  logger.info("Transform to {}", args(1))

  private val routeToStops: List[Entry] = agencies
    .map(name => name -> source.resolve(s"$name.zip"))
    .map {
      case (name, path) =>
        extractStops(name, path)
    }
    .reduce(_ ++ _)

  saveToCSV(routeToStops, Paths.get(args(1)))

  private case class Entry(fileName: String, route: Route, stops: Seq[Stop])

  private def extractStops(fileName: String, gtfsPath: Path): List[Entry] = {
    val dao = GtfsUtils.createDAO(gtfsPath)
    val routeToStop = dao.getAllTrips.asScala
      .groupBy(_.getRoute)
      .mapValues(_.head)
      .mapValues(trip => dao.getStopTimesForTrip(trip).asScala)
      .mapValues(stopTimes => stopTimes.map(_.getStop))

    routeToStop.map {
      case (route, stops) =>
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
      } yield
        (writer.write(
          e.fileName,
          e.route.getAgency.getId,
          e.route.getId.getId,
          Option(e.route.getShortName),
          stop.getId.getId,
          stop.getLat,
          stop.getLon
        ))
    }
  }
}
