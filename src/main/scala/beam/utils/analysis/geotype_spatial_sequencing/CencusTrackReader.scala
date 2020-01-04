package beam.utils.analysis.geotype_spatial_sequencing

import beam.utils.csv.GenericCsvReader
import com.typesafe.scalalogging.LazyLogging

import scala.util.Try

object CencusTrackReader extends LazyLogging {
  private def toCencusTrack(rec: java.util.Map[String, String]): Option[CencusTrack] = {
    // STATEFP,COUNTYFP,TRACTCE,POPULATION,LATITUDE,LONGITUDE
    val maybeState = Option(rec.get("STATEFP"))
    val maybeCountry = Option(rec.get("COUNTYFP"))
    val maybeTract = Option(rec.get("TRACTCE"))
    val maybePopulation = Option(rec.get("POPULATION")).map(_.toInt)
    val maybeLatitude = Option(rec.get("LATITUDE")).map(_.toDouble)
    val maybeLongitude = Option(rec.get("LONGITUDE")).map(_.toDouble)
    val cencusTrack = for {
      state      <- maybeState
      country    <- maybeCountry
      tract      <- maybeTract
      population <- maybePopulation
      latitude   <- maybeLatitude
      longitude  <- maybeLongitude
    } yield
      CencusTrack(
        state = state,
        country = country,
        tract = tract,
        population = population,
        latitude = latitude,
        longitude = longitude
      )

    if (cencusTrack.isEmpty) {
      logger.warn(s"Record has empty fields, so considering it as empty. Map: ${rec}")
    }
    cencusTrack
  }

  def readFromCsv(path: String): Array[CencusTrack] = {
    val (iter, toClose) = GenericCsvReader.readAs[Option[CencusTrack]](path, toCencusTrack, _ => true)
    try {
      iter.flatten.toArray
    } finally {
      Try(toClose.close())
    }
  }
}
