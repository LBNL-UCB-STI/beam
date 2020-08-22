package beam.utils

import java.io.StringReader
import java.lang.Math.abs
import java.time.Instant
import java.util

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import beam.utils.FileUtils.readAllLines
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig.GoogleapiFiles$Elm
import beam.utils.google_routes_db.json._
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.parser.decode
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.concurrent.{ExecutionContext, Future}

package object google_routes_db extends LazyLogging {

  case class GoogleapiFiles(
    googleapiResponsesJsonFileUri: String,
    maybeTimestamp: Option[Instant],
    googleTravelTimeEstimationCsvText: String,
    googleapiResponsesJsonText: String
  )

  def sourceGoogleapiFiles
    (config: GoogleRoutesDBConfig)
      (implicit AS: ActorSystem, EC: ExecutionContext): Source[GoogleapiFiles, NotUsed] =
    Source(config.googleapiFiles)
      .mapAsync(1) {

        // HTTP sourcing (e.g. s3 bucket)
        case GoogleapiFiles$Elm(Some(http), _) ⇒
          val googleapiResponsesJsonFileUri = http.googleapiResponsesJsonFile.get
          val googleTravelTimeEstimationCsvFileUri = http.googleTravelTimeEstimationCsvFile.get

          logger.info(s"Downloading $googleapiResponsesJsonFileUri")
          val resps = downloadAsString(googleapiResponsesJsonFileUri)

          logger.info(s"Downloading $googleTravelTimeEstimationCsvFileUri")
          val reqs = downloadAsString(googleTravelTimeEstimationCsvFileUri)

          for {
            googleTravelTimeEstimationCsvText  ← reqs
            googleapiResponsesJsonText ← resps
          } yield {
            GoogleapiFiles(
              googleapiResponsesJsonFileUri,
              parseBeamOutputTimestamp(googleapiResponsesJsonFileUri),
              googleTravelTimeEstimationCsvText,
              googleapiResponsesJsonText,
            )
          }

        // Local files sourcing
        case GoogleapiFiles$Elm(None, Some(local)) ⇒
          val googleapiResponsesJsonFileLoc = local.googleapiResponsesJsonFile.get
          val googleTravelTimeEstimationCsvFileLoc = local.googleTravelTimeEstimationCsvFile.get

          logger.info(s"Reading local $googleapiResponsesJsonFileLoc")
          logger.info(s"Reading local $googleTravelTimeEstimationCsvFileLoc")

          Future {
            GoogleapiFiles(
              googleapiResponsesJsonFileLoc,
              parseBeamOutputTimestamp(googleapiResponsesJsonFileLoc),
              readAllLines(googleTravelTimeEstimationCsvFileLoc).mkString("\n"),
              readAllLines(googleapiResponsesJsonFileLoc).mkString("\n")
            )
          }

        case _ ⇒
          Future.failed(new IllegalArgumentException(
            "google_routes_db config is corrupted"
          ))
      }

  def downloadAsString
    (uri: Uri)
      (implicit AS: ActorSystem, EC: ExecutionContext): Future[String] = {
    Http().singleRequest(HttpRequest(uri = uri))
      .flatMap { resp ⇒
        resp.entity.httpEntity
          .withSizeLimit(134217728L)
          .dataBytes
          .runReduce(_ ++ _)
          .map(_.utf8String)
      }
  }

  def parseBeamOutputTimestamp(path: String): Option[Instant] = {
    val p = ".*__(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)_(\\d\\d)-(\\d\\d)-(\\d\\d)_.*".r
    path match {
      case p(y, m, d, h, mi, s) ⇒
        Some(Instant.parse(s"$y-$m-${d}T$h:$mi:$s.000Z"))
      case _ ⇒ None
    }
  }

  def parseGoogleTravelTimeEstimationCsv(text: String): Seq[Map[String, String]] = {
    val csvReader = new CsvMapReader(new StringReader(text), CsvPreference.STANDARD_PREFERENCE)
    val header = csvReader.getHeader(true)
    val result = new mutable.ArrayBuffer[Map[String, String]]()

    Iterator
      .continually(csvReader.read(header: _*))
      .takeWhile(_ != null)
      .foreach { entry: util.Map[String, String] =>
        result.append(entry.asScala.toMap)
      }

    result
  }

  def coordsNearby(
    c1: json.GoogleRoute.Coord,
    c2: json.GoogleRoute.Coord,
    epsilon: Double
  ): Boolean = abs(c1.lat - c2.lat) < epsilon && abs(c1.lng - c2.lng) < epsilon

  def getSizeFrequencies(map: Map[_, Seq[_]]): Map[Int, Int] = {
    val freq = mutable.LinkedHashMap[Int, Int]()
    map.foreach { case (_, seq) ⇒
      freq(seq.size) = freq.getOrElse(seq.size, 0) + 1
    }

    freq.toMap
  }

  def parseGoogleapiResponsesJson
    (text: String)
      (implicit D: Decoder[immutable.Seq[json.GoogleRoutes]]): immutable.Seq[json.GoogleRoutes] = {
    decode[immutable.Seq[GoogleRoutes]](text) match {
      case Right(json) ⇒ json
      case Left(e) ⇒
        val head = text.take(200).replaceAll("\\s+", "")
        logger.warn(s"Failed to parse GoogleRoutes (<$head...>): ${e.getMessage}")
        immutable.Seq.empty
    }
  }
}
