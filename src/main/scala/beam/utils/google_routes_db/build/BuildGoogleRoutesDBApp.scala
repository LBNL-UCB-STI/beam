package beam.utils.google_routes_db.build

import java.time.Instant
import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.stream.scaladsl._
import beam.agentsim.events.handling.GoogleTravelTimeEstimationEntry
import beam.sim.BeamHelper
import beam.utils.FileUtils.using
import beam.utils.google_routes_db.GoogleRoutesDB
import beam.utils.google_routes_db.build.config.BuildGoogleRoutesDBConfig
import beam.utils.mapsapi.googleapi.GoogleRoutesResponse
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Issue #2872 "TravelTimeGoogleStatistic: Build database of the computed routes"
  * https://github.com/LBNL-UCB-STI/beam/issues/2872
  *
  * Run with Gradle:
  * ./gradlew :execute \
  *   -PmainClass=beam.utils.google_routes_db.build.BuildGoogleRoutesDBApp \
  *   -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/build/config/build_google_routes_db.conf']" \
  *   -PlogbackCfg=logback.xml
  */
object BuildGoogleRoutesDBApp extends BeamHelper {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  //noinspection DuplicatedCode
  def main(args: Array[String]): Unit =
    try {
      val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
      val config = BuildGoogleRoutesDBConfig(cfg)

      val dataSource = makeDataSource(
        config.postgresql.url,
        config.postgresql.username,
        config.postgresql.password
      )

      val doneFuture: Future[Done] =
        Source
          .future(Future {
            using(dataSource.getConnection) { con =>
              GoogleRoutesDB.createGoogleRoutesTables(con)
            }
          })
          .flatMapConcat { _ => sourceGoogleapiFiles(config) }
          .mapAsync(1) { googleapiFiles =>

            val grrSeq: Seq[GoogleRoutesResponse] =
              GoogleRoutesResponse.Json.decodeGoogleRoutesResponses(
                googleapiFiles.googleapiResponsesJsonText
              )

            val gttees =
              GoogleTravelTimeEstimationEntry.Csv.parseEntries(
                googleapiFiles.googleTravelTimeEstimationCsvText
              )

            val requestIdToDepartureTime: Map[String, Int] =
              gttees.groupBy(_.requestId).mapValues(_.head.departureTime)

            Future({
              using(dataSource.getConnection) { con =>
                GoogleRoutesDB.insertGoogleRoutesAndLegs(
                  grrSeq,
                  requestIdToDepartureTime,
                  googleapiFiles.googleapiResponsesJsonFileUri,
                  googleapiFiles.maybeTimestamp.getOrElse(Instant.now),
                  con
                )
              }
            })
          }
          .toMat(Sink.ignore)(Keep.right)
          .run()

      doneFuture.onComplete {
        case Success(_) =>
        case Failure(e) =>
          logger.error("An error occurred", e)
      }

      Await.ready(doneFuture, 300.minutes)
      Await.ready(system.terminate(), 1.minute)
      System.exit(0)
    } catch {
      case e: Throwable =>
        logger.error("GoogleRoutesDB failed", e)

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
}
