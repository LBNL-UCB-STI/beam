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

  private def withLeftPadding(id: String): String = {
    "%011d".format(id.toLong)
  }

  private def toTrip(rec: java.util.Map[String, String]): Option[Trip] = {
    // o_geoid,d_geoid
    val maybeOrigin = Option(rec.get("o_geoid")).map(withLeftPadding)
    maybeOrigin.foreach(checkTractFormat)
    val maybeDest = Option(rec.get("d_geoid")).map(withLeftPadding)
    maybeDest.foreach(checkTractFormat)
    val maybeTrips = Some(1)
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
