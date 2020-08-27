package beam.utils.google_routes_db

import java.sql.Statement
import java.time.Instant
import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.stream.scaladsl._
import beam.sim.BeamHelper
import beam.utils.FileUtils.using
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig
import beam.utils.google_routes_db.{request => req, response => resp}
import beam.utils.google_routes_db.sql.BatchUpdateGraphStage
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
 *   -PmainClass=beam.utils.google_routes_db.GoogleRoutesDB \
 *   -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/config/google_routes_db.conf']" \
 *   -PlogbackCfg=logback.xml
 */
object GoogleRoutesDB extends BeamHelper {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  //noinspection DuplicatedCode
  def main(args: Array[String]): Unit = try {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val config = GoogleRoutesDBConfig(cfg)

    val dataSource = makeDataSource(
      config.postgresql.url,
      config.postgresql.username,
      config.postgresql.password
    )

    val epsilon = 0.0001
    logger.info(s"epsilon = $epsilon")

    val (batchUpdateFuture, doneFuture) =
      Source
        .future(createGoogleRoutesTables(dataSource))
        .flatMapConcat { _ => sourceGoogleapiFiles(config) }
        .mapAsync(1) { googleapiFiles =>

          val grsSeq: immutable.Seq[resp.GoogleRoutesResponse] =
            resp.json.parseGoogleapiResponsesJson(
              googleapiFiles.googleapiResponsesJsonText
            )

          val requests =
            req.csv.parseGoogleTravelTimeEstimationCsv(
              googleapiFiles.googleTravelTimeEstimationCsvText
            )

          val departureTimes: Map[String, Option[Int]] = requests.groupBy(_.requestId)
            .mapValues(_.headOption.map(_.departureTime))

          Future.sequence {
            grsSeq.map { resp =>
              insertGoogleRoutes(
                immutable.Seq(resp.response.routes: _*),
                resp.requestId,
                departureTimes.get(resp.requestId).flatten,
                googleapiFiles.googleapiResponsesJsonFileUri,
                googleapiFiles.maybeTimestamp.getOrElse(Instant.now),
                dataSource
              )
            }
          }.map(_.flatten)
        }
        .flatMapConcat { routesWithIds: immutable.Seq[(resp.GoogleRoute, Int)] =>
          Source(
            routesWithIds.flatMap { case (gr, routeId) =>
              gr.legs.map { leg => sql.Update.GoogleRouteLeg.fromResp(routeId, leg) }
            }
          )
        }
        .grouped(1000)
        .viaMat(
          Flow.fromGraph(
            new BatchUpdateGraphStage[sql.Update.GoogleRouteLeg](
              dataSource,
              con => con.prepareStatement(sql.Update.GoogleRouteLeg.insertSql)
            )
          )
        )(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

    val allDoneFuture = for {
      batchUpdateResult <- batchUpdateFuture
      _ <- Future { logger.info(
        "Routes insertion stats: batchesProcessed={}, itemsProcessed={}, rowsUpdated={}",
        batchUpdateResult.batchesProcessed,
        batchUpdateResult.itemsProcessed,
        batchUpdateResult.rowsUpdated
      )}
      _ <- doneFuture
    } yield Done

    allDoneFuture.onComplete {
      case Success(_) =>
      case Failure(e) =>
        logger.error("An error occurred", e)
        e.printStackTrace()
    }

    Await.ready(allDoneFuture, 300.minutes)
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

  private def createGoogleRoutesTables(dataSource: DataSource)
      (implicit executor: ExecutionContext): Future[Done] = {
    Future({
      using(dataSource.getConnection) { con =>
        using(con.prepareStatement(sql.DDL.googleRouteTable)) { ps => ps.execute() }
        using(con.prepareStatement(sql.DDL.legTable)) { ps => ps.execute() }
      }

      Done
    })(executor)
  }

  private def insertGoogleRoutes(
    grs: immutable.Seq[resp.GoogleRoute],
    requestId: String,
    departureTime: Option[Int],
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant,
    dataSource: DataSource
  )(implicit executor: ExecutionContext): Future[immutable.Seq[(resp.GoogleRoute, Int)]] = Future({
    using(dataSource.getConnection) { con =>
      using(
        con.prepareStatement(
          sql.Update.GoogleRoute.insertSql,
          Statement.RETURN_GENERATED_KEYS
        )
      ) { ps =>
        grs.foreach { gr =>
          sql.Update.GoogleRoute.psMapping.mapPrepared(
            sql.Update.GoogleRoute.fromResp(
              googleRoute = gr,
              requestId = requestId,
              departureTime = departureTime,
              googleapiResponsesJsonFileUri = Some(googleapiResponsesJsonFileUri),
              timestamp = timestamp
            ),
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

        grs.map { gr =>
          keysRS.next()
          (gr, keysRS.getInt(1))
        }
      }
    }
  })(executor)
}
