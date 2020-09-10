package beam.utils.google_routes_db

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import beam.utils.FileUtils.readAllLines
import beam.utils.google_routes_db.build.config.BuildGoogleRoutesDBConfig
import beam.utils.google_routes_db.build.config.BuildGoogleRoutesDBConfig._
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

package object build extends LazyLogging {

  case class GoogleapiFiles(
    googleapiResponsesJsonFileUri: String,
    maybeTimestamp: Option[Instant],
    googleTravelTimeEstimationCsvText: String,
    googleapiResponsesJsonText: String
  )

  def sourceGoogleapiFiles
    (config: BuildGoogleRoutesDBConfig)
      (implicit AS: ActorSystem, EC: ExecutionContext): Source[GoogleapiFiles, NotUsed] =
    Source(config.googleapiFiles)
      .mapAsync(1) {

        // HTTP sourcing (e.g. s3 bucket)
        case GoogleapiFiles$Elm(Some(http), _) =>
          val googleapiResponsesJsonFileUri = http.googleapiResponsesJsonFile.get
          val googleTravelTimeEstimationCsvFileUri = http.googleTravelTimeEstimationCsvFile.get

          logger.info(s"Downloading $googleapiResponsesJsonFileUri")
          val resps = downloadAsString(googleapiResponsesJsonFileUri)

          logger.info(s"Downloading $googleTravelTimeEstimationCsvFileUri")
          val reqs = downloadAsString(googleTravelTimeEstimationCsvFileUri)

          for {
            googleTravelTimeEstimationCsvText  <- reqs
            googleapiResponsesJsonText <- resps
          } yield {
            GoogleapiFiles(
              googleapiResponsesJsonFileUri,
              parseBeamOutputTimestamp(googleapiResponsesJsonFileUri),
              googleTravelTimeEstimationCsvText,
              googleapiResponsesJsonText,
            )
          }

        // Local files sourcing
        case GoogleapiFiles$Elm(None, Some(local)) =>
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

        case _ =>
          Future.failed(new IllegalArgumentException(
            "google_routes_db config is corrupted"
          ))
      }

  def downloadAsString
    (uri: Uri)
      (implicit AS: ActorSystem, EC: ExecutionContext): Future[String] = {
    Http().singleRequest(HttpRequest(uri = uri))
      .flatMap { resp =>
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
      case p(y, m, d, h, mi, s) =>
        Some(Instant.parse(s"$y-$m-${d}T$h:$mi:$s.000Z"))
      case _ => None
    }
  }

  def getSizeFrequencies(map: Map[_, Seq[_]]): Map[Int, Int] = {
    val freq = mutable.LinkedHashMap[Int, Int]()
    map.foreach { case (_, seq) =>
      freq(seq.size) = freq.getOrElse(seq.size, 0) + 1
    }

    freq.toMap
  }
}
