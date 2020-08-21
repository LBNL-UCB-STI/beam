package beam.utils.google_routes_db

import java.io.StringReader
import java.lang.Math.abs
import java.sql.Statement
import java.time.Instant
import java.util
import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import beam.sim.BeamHelper
import beam.utils.FileUtils.{readAllLines, using}
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig.GoogleapiFiles$Elm
import beam.utils.google_routes_db.json._
import beam.utils.google_routes_db.sql.BatchUpdateGraphStage
import io.circe.parser.decode
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Issue #2872 "TravelTimeGoogleStatistic: Build database of the computed routes"
 * https://github.com/LBNL-UCB-STI/beam/issues/2872
 *
 *  * Run with gradle:
 * ./gradlew :execute \
 *   -PmainClass=beam.utils.google_routes_db.GoogleRoutesDB \
 *   -PappArgs="['--json-urls-file','beam/src/main/scala/beam/utils/google_routes_db/googleapi_responses_urls.txt','--pg-url','jdbc:postgresql://localhost:5432/google_routes_db','--pg-user','postgres','--pg-pass','postgres']" \
 *   -PlogbackCfg=logback.xml
 */
object GoogleRoutesDB extends BeamHelper {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def main(args: Array[String]): Unit = try {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val config = GoogleRoutesDBConfig(cfg)

    val dataSource = makeDataSource(
      config.postgresql.url,
      config.postgresql.username,
      config.postgresql.password
    )

    //val eps = 0.00001  // Matched group size frequencies: 0 -> 1732,  1 -> 0, 2 -> 26,   3 -> 0,  4 -> 78,   5 -> 5,   6 -> 612
    val eps = 0.0001     // Matched group size frequencies: 0 -> 114,   1 -> 0, 2 -> 128,  3 -> 0,  4 -> 312,  5 -> 15,  6 -> 1884
    //val eps = 0.001    // Matched group size frequencies: 0 -> 20,    1 -> 0, 2 -> 126,  3 -> 0,  4 -> 324,  5 -> 15,  6 -> 1968
    logger.info(s"epsilon = $eps")

    val batchUpdateFlow: Flow[
      immutable.Seq[sql.Update.GoogleRouteLeg],
      immutable.Seq[sql.Update.GoogleRouteLeg],
      Future[BatchUpdateGraphStage.Result]
    ] = Flow.fromGraph(
      new BatchUpdateGraphStage[sql.Update.GoogleRouteLeg](
        dataSource,
        con ⇒ con.prepareStatement(sql.Update.GoogleRouteLeg.insertSql)
      )
    )

    val sink: Sink[Any, Future[Done]] = Sink.ignore  // just consume the steam

    val (batchUpdateFuture, doneFuture) = Source
      .future(createGoogleRoutesTables(dataSource))
      .flatMapConcat { _ ⇒ Source(config.googleapiFiles) }
      .mapAsync[(String, Option[Instant], String, String)](2) {

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
          } yield {(
            googleapiResponsesJsonFileUri,
            parseTimestampFromUri(googleapiResponsesJsonFileUri),
            googleTravelTimeEstimationCsvText,
            googleapiResponsesJsonText,
          )}

        // Local files sourcing
        case GoogleapiFiles$Elm(None, Some(local)) ⇒
          val googleapiResponsesJsonFileLoc = local.googleapiResponsesJsonFile.get
          val googleTravelTimeEstimationCsvFileLoc = local.googleTravelTimeEstimationCsvFile.get

          logger.info(s"Reading local $googleapiResponsesJsonFileLoc")
          logger.info(s"Reading local $googleTravelTimeEstimationCsvFileLoc")

          Future((
            googleapiResponsesJsonFileLoc,
            parseTimestampFromUri(googleapiResponsesJsonFileLoc),
            readAllLines(googleTravelTimeEstimationCsvFileLoc).mkString("\n"),
            readAllLines(googleapiResponsesJsonFileLoc).mkString("\n")
          ))

        case _ ⇒
          Future.failed(new IllegalArgumentException(
            "google_routes_db config is corrupted"
          ))
      }
      .mapAsync(1) { case (uri, maybeTimestamp, reqsText, respsText) ⇒
        val requests = parseGoogleTravelTimeEstimationCsv(reqsText)
        val grsSeq: immutable.Seq[json.GoogleRoutes] = parseGoogleapiResponsesJson(respsText)

        val rrMapping: Map[Map[String, String], (Seq[GoogleRoute], Seq[Map[String, String]])] =
          requests.map { request ⇒
            val matchedResponses: Seq[GoogleRoute] = grsSeq.flatMap(_.routes).filter { r ⇒
                abs(r.legs.head.startLocation.lat - request("originLat").toDouble) < eps &&
                abs(r.legs.head.startLocation.lng - request("originLng").toDouble) < eps &&
                abs(r.legs.head.endLocation.lat - request("destLat").toDouble) < eps &&
                abs(r.legs.head.endLocation.lng - request("destLng").toDouble) < eps
            }

            val nearbyRequests: Seq[Map[String, String]] = requests.filter { otherRequest ⇒
              request != otherRequest &&
                abs(request("originLat").toDouble - otherRequest("originLat").toDouble) < eps &&
                abs(request("originLng").toDouble - otherRequest("originLng").toDouble) < eps &&
                abs(request("destLat").toDouble - otherRequest("destLat").toDouble) < eps &&
                abs(request("destLng").toDouble - otherRequest("destLng").toDouble) < eps
            }
            (request, (matchedResponses, nearbyRequests))
          }.toMap

        // Linked for readable output
        val matchedSizes = mutable.LinkedHashMap[Int, Int]()
        val nearbySizes = mutable.LinkedHashMap[Int, Int]()
        val legsSizes = mutable.LinkedHashMap[Int, Int]()
        for (i ← 0 until 6) {
          matchedSizes(i) = 0
          nearbySizes(i) = 0
          legsSizes(i) = 0
        }

        rrMapping.foreach { case (_, (routes, nearbyRequests)) ⇒
          matchedSizes(routes.size) = matchedSizes.getOrElse(routes.size, 0) + 1
          nearbySizes(nearbyRequests.size) = nearbySizes.getOrElse(nearbyRequests.size, 0) + 1
          routes.foreach { route ⇒
            val legsSize = route.legs.size
            legsSizes(legsSize) = legsSizes.getOrElse(legsSize, 0) + 1
          }
        }

        logger.info(s"Matched group size frequencies:\n$matchedSizes")
        logger.info(s"Nearby requests size frequencies:\n$nearbySizes")
        //logger.info(s"GoogleRoute legs size frequencies:\n$legsSizes")

        insertGoogleRoutes(
          dataSource,
          grsSeq.flatMap(_.routes),
          uri,
          maybeTimestamp.getOrElse(Instant.now)
        )
      }
      .flatMapConcat { routesWithIds: immutable.Seq[(GoogleRoute, Int)] ⇒
        Source(
          routesWithIds.flatMap { case (gr, routeId) ⇒
            gr.legs.map { leg ⇒ sql.Update.GoogleRouteLeg.fromJson(routeId, leg) }
          }
        )
      }
      .grouped(1000)
      .viaMat(batchUpdateFlow)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()

    val allDoneFuture = for {
      batchUpdateResult ← batchUpdateFuture
      _ ← Future { logger.info(
        "Routes insertion stats: batchesProcessed={}, itemsProcessed={}, rowsUpdated={}",
        batchUpdateResult.batchesProcessed,
        batchUpdateResult.itemsProcessed,
        batchUpdateResult.rowsUpdated
      )}
      _ ← doneFuture
    } yield Done

    allDoneFuture.onComplete {
      case Success(_) ⇒
      case Failure(e) ⇒
        logger.error("An error occurred: {}", e.getMessage)
        e.printStackTrace()
    }

    Await.ready(allDoneFuture, 300.minutes)
    Await.ready(system.terminate(), 1.minute)
    System.exit(0)
  } catch {
    case e: Throwable ⇒
      e.printStackTrace()

      Await.ready(system.terminate(), 1.minute)
      System.exit(1)
  }

  private def makeDataSource(pgUrl: String, pgUser: String, pgPass: String): DataSource = {
    val ds = new BasicDataSource()
    ds.setDriverClassName("org.postgresql.Driver")
    ds.setUrl(pgUrl)
    ds.setUsername(pgUser)
    ds.setPassword(pgPass)

    ds
  }

  private def createGoogleRoutesTables(dataSource: DataSource)
      (implicit executor: ExecutionContext): Future[Done] = {
    Future({
      using(dataSource.getConnection) { con ⇒
        using(con.prepareStatement(sql.DDL.googleRouteTable)) { ps ⇒ ps.execute() }
        using(con.prepareStatement(sql.DDL.legTable)) { ps ⇒ ps.execute() }
      }

      Done
    })(executor)
  }

  private def insertGoogleRoutes(
    dataSource: DataSource,
    grs: immutable.Seq[json.GoogleRoute],
    outputFileUri: String,
    timestamp: Instant
  )(implicit executor: ExecutionContext): Future[immutable.Seq[(json.GoogleRoute, Int)]] = Future({
    using(dataSource.getConnection) { con ⇒
      using(
        con.prepareStatement(
          sql.Update.GoogleRoute.insertSql,
          Statement.RETURN_GENERATED_KEYS
        )
      ) { ps ⇒
        grs.foreach { gr ⇒
          sql.Update.GoogleRoute.psMapping.mapPrepared(
            sql.Update.GoogleRoute.fromJson(gr, Some(outputFileUri), timestamp),
            ps
          )
          ps.addBatch()
        }
        ps.executeBatch()

        logger.debug(
          "Inserted routes: {}",
          grs.map(_.summary).mkString("[", ", ", "]")
        )

        val keysRS = ps.getGeneratedKeys

        grs.map { gr ⇒
          keysRS.next()
          (gr, keysRS.getInt(1))
        }
      }
    }
  })(executor)

  private def downloadAsString(uri: Uri): Future[String] = {
    Http().singleRequest(HttpRequest(uri = uri))
      .flatMap { resp ⇒
        resp.entity.httpEntity
          .withSizeLimit(134217728L)
          .dataBytes
          .runReduce(_ ++ _)
          .map(_.utf8String)
      }
  }

  private def parseTimestampFromUri(uri: String): Option[Instant] = {
    val p = ".*__(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)_(\\d\\d)-(\\d\\d)-(\\d\\d)_.*".r
    uri match {
      case p(y, m, d, h, mi, s) ⇒
        Some(Instant.parse(s"$y-$m-${d}T$h:$mi:$s.000Z"))
      case _ ⇒ None
    }
  }

  private def parseGoogleTravelTimeEstimationCsv(text: String): Seq[Map[String, String]] = {
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

  private def parseGoogleapiResponsesJson(text: String): immutable.Seq[json.GoogleRoutes] = {
    decode[immutable.Seq[GoogleRoutes]](text) match {
      case Right(json) ⇒ json
      case Left(e) ⇒
        val head = text.take(200).replaceAll("\\s+", "")
        logger.warn(s"Failed to parse GoogleRoutes (<$head...>): ${e.getMessage}")
        immutable.Seq.empty
    }
  }
}
