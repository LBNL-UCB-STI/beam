package beam.utils.analysis.geotype_spatial_sequencing

import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

object TripReader extends LazyLogging {
  private def checkTractFormat(s: String): Unit = {
    if (s.length != 11) {
      logger.warn(s"Expecting to have a tract with length 11, something might be wrong!")
    }
  }
  private def toTrip(rec: java.util.Map[String, String]): Option[Trip] = {
    // w_tract,h_tract,S000
    val maybeOrigin = Option(rec.get("w_tract"))
    maybeOrigin.foreach(checkTractFormat)
    val maybeDest = Option(rec.get("h_tract"))
    maybeDest.foreach(checkTractFormat)
    val maybeTrips = Option(rec.get("S000")).flatMap(maybeNum => Try(maybeNum.toInt).toOption)
    val trip = for {
      origin <- maybeOrigin
      dest   <- maybeDest
      trips  <- maybeTrips
    } yield
      Trip(
        origin = origin,
        dest = dest,
        trips = trips
      )
    if (trip.isEmpty) {
      logger.warn(s"Record has empty fields, so considering it as empty. Map: ${rec}")
    }
    trip
  }

  def readFromCsv(path: String): Array[Trip] = {
    val (iter, toClose) = GenericCsvReader.readAs[Option[Trip]](path, toTrip, _ => true)
    try {
      iter.flatten.toArray
    } finally {
      Try(toClose.close())
    }
  }
}
