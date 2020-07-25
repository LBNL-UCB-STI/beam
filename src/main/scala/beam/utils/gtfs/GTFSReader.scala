package beam.utils.gtfs

import java.io.{Closeable, InputStream}
import java.util.zip.ZipFile

import beam.utils.csv.GenericCsvReader
import beam.utils.gtfs.Model._
import com.typesafe.scalalogging.LazyLogging

import scala.reflect.ClassTag

object GTFSReader extends LazyLogging {

  private def readGTFSStream[T](stream: InputStream, mapFunc: java.util.Map[String, String] => T)(
    implicit ct: ClassTag[T]
  ): Set[T] = {
    val (iter: Iterator[T], toClose: Closeable) =
      GenericCsvReader.readFromStreamAs[T](stream, mapFunc, _ => true)
    try {
      iter.toSet
    } finally {
      toClose.close()
    }
  }

  private def readGTFSArchive[T](
    gtfsZip: ZipFile,
    fileName: String,
    mapFunc: java.util.Map[String, String] => T
  )(
    implicit ct: ClassTag[T]
  ): Set[T] = {
    val entry = gtfsZip.getEntry(fileName)
    if (entry == null) {
      logger.warn(s"A GTFS archive ${gtfsZip.getName} does not contain a file '$fileName'")
      Set.empty[T]
    } else {
      val stream = gtfsZip.getInputStream(entry);
      val items = readGTFSStream(stream, mapFunc)
      logger.info(s"Read ${items.size} items from $fileName")
      items
    }
  }

  def readRoutes(gtfsZip: ZipFile, sourceName: String, defaultAgencyId: String): Set[Route] =
    readGTFSArchive(gtfsZip, "routes.txt", Route.apply(sourceName, defaultAgencyId))

  def readAgencies(gtfsZip: ZipFile, sourceName: String): Set[Agency] =
    readGTFSArchive(gtfsZip, "agency.txt", Agency.apply(sourceName))

  def readTrips(gtfsZip: ZipFile): Set[Trip] = readGTFSArchive(gtfsZip, "trips.txt", Trip.apply)
  def readStopTimes(gtfsZip: ZipFile): Set[StopTime] = readGTFSArchive(gtfsZip, "stop_times.txt", StopTime.apply)
  def readStops(gtfsZip: ZipFile): Set[Stop] = readGTFSArchive(gtfsZip, "stops.txt", Stop.apply)
}
