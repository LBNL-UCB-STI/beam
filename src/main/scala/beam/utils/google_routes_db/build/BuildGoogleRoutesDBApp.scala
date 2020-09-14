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
import beam.utils.google_routes_db.GoogleRoutesDB.InsertedGoogleRouteId
import beam.utils.google_routes_db.build.config.BuildGoogleRoutesDBConfig
import beam.utils.google_routes_db.sql.Update
import beam.utils.google_routes_db.{sql, GoogleRoute, GoogleRoutesDB, GoogleRoutesResponse}
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource

import scala.collection.immutable
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
  *   -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/config/build/google_routes_db.conf']" \
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
          .flatMapConcat { _ =>
            sourceGoogleapiFiles(config)
          }
          .mapAsync(1) { googleapiFiles =>
            val grrSeq: immutable.Seq[GoogleRoutesResponse] =
              GoogleRoutesResponse.Json.parseGoogleapiResponsesJson(
                googleapiFiles.googleapiResponsesJsonText
              )

            val gttees =
              GoogleTravelTimeEstimationEntry.Csv.parseEntries(
                googleapiFiles.googleTravelTimeEstimationCsvText
              )

            val requestIdToDepartureTime: Map[String, Option[Int]] =
              gttees.groupBy(_.requestId).mapValues(_.headOption.map(_.departureTime))

            insertGoogleRoutesAndLegs(
              grrSeq,
              requestIdToDepartureTime,
              googleapiFiles.googleapiResponsesJsonFileUri,
              googleapiFiles.maybeTimestamp.getOrElse(Instant.now),
              dataSource
            )
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

  private def insertGoogleRoutesAndLegs(
    grrSeq: Seq[GoogleRoutesResponse],
    requestIdToDepartureTime: Map[String, Option[Int]],
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant,
    dataSource: DataSource
  )(implicit executor: ExecutionContext): Future[Done] =
    Future({
      using(dataSource.getConnection) {
        con =>
          grrSeq.foreach {
            grr: GoogleRoutesResponse =>
              val grItemToGr: Map[Update.GoogleRouteItem, GoogleRoute] =
                createGoogleRouteItems(
                  grr.response.routes,
                  grr.requestId,
                  requestIdToDepartureTime.get(grr.requestId).flatten,
                  googleapiResponsesJsonFileUri,
                  timestamp
                )

              val grItemToId: Map[Update.GoogleRouteItem, InsertedGoogleRouteId] =
                GoogleRoutesDB.insertGoogleRoutes(grItemToGr.keys.toSeq, con)

              GoogleRoutesDB.insertGoogleRouteLegs(
                createGoogleRouteLegItems(
                  grItemToId.flatMap {
                    case (grItem, routeId) =>
                      grItemToGr(grItem).legs.map(leg => (routeId, leg))
                  }.toSeq
                ),
                con
              )
          }
      }

      Done
    })(executor)

  private def createGoogleRouteItems(
    grs: Seq[GoogleRoute],
    requestId: String,
    departureTime: Option[Int],
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant,
  ): Map[sql.Update.GoogleRouteItem, GoogleRoute] = {
    grs.map { gr =>
      val item = sql.Update.GoogleRouteItem.create(
        googleRoute = gr,
        requestId = requestId,
        departureTime = departureTime,
        googleapiResponsesJsonFileUri = Some(googleapiResponsesJsonFileUri),
        timestamp = timestamp
      )
      (item, gr)
    }.toMap
  }

  private def createGoogleRouteLegItems(
    routeIdLegs: Seq[(InsertedGoogleRouteId, GoogleRoute.Leg)]
  ): Seq[sql.Update.GoogleRouteLegItem] = {
    routeIdLegs.map {
      case (routeId, leg) =>
        sql.Update.GoogleRouteLegItem.create(routeId, leg)
    }
  }

//  private def insertGoogleRoutes(
//    grs: immutable.Seq[GoogleRoute],
//    requestId: String,
//    departureTime: Option[Int],
//    googleapiResponsesJsonFileUri: String,
//    timestamp: Instant,
//    dataSource: DataSource
//  )(implicit executor: ExecutionContext): Future[immutable.Seq[(GoogleRoute, Int)]] = Future({
//    using(dataSource.getConnection) { con =>
//      using(
//        con.prepareStatement(
//          sql.Update.GoogleRouteItem.insertSql,
//          Statement.RETURN_GENERATED_KEYS
//        )
//      ) { ps =>
//        grs.foreach { gr =>
//          sql.Update.GoogleRouteItem.psMapping.mapPrepared(
//            sql.Update.GoogleRouteItem.create(
//              googleRoute = gr,
//              requestId = requestId,
//              departureTime = departureTime,
//              googleapiResponsesJsonFileUri = Some(googleapiResponsesJsonFileUri),
//              timestamp = timestamp
//            ),
//            ps
//          )
//          ps.addBatch()
//        }
//        ps.executeBatch()
//
//        logger.debug(
//          "Inserted routes: {}",
//          grs.map(_.summary).mkString("[", ", ", "]")
//        )
//
//        val keysRS = ps.getGeneratedKeys
//
//        grs.map { gr =>
//          keysRS.next()
//          (gr, keysRS.getInt(1))
//        }
//      }
//    }
//  })(executor)
}
