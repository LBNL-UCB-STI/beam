package beam.utils.google_routes_db

import java.nio.file.Paths
import java.sql.Statement
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import beam.utils.FileUtils.using
import beam.utils.google_routes_db.json._
import beam.utils.google_routes_db.sql.BatchUpdateGraphStage
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.decode
import javax.sql.DataSource
import org.apache.commons.dbcp2.BasicDataSource
import scopt.OptionParser

import scala.collection.immutable
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
object GoogleRoutesDB extends LazyLogging {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def main(args: Array[String]): Unit = {
    val arguments = parseArguments(args)

    val dataSource = makeDataSource(
      arguments.pgUrl.get,
      arguments.pgUser.get,
      arguments.pgPass.get
    )

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

    val uriSource: Source[Uri, NotUsed] =
      FileIO.fromPath(Paths.get(arguments.jsonUrlsFile.get))
        .via(Framing.delimiter(ByteString("\n"), 512))
        .map(_.utf8String)
        .map(Uri(_))
        .mapMaterializedValue(_ ⇒ NotUsed)

    val sink: Sink[Any, Future[Done]] = Sink.ignore  // just consume the steam

    val (batchUpdateFuture, doneFuture) = Source
      .future(createGoogleRoutesTables(dataSource))
      .flatMapConcat { _ ⇒ uriSource }
      .mapAsync(2) { uri ⇒
        logger.info(s"Downloading $uri")
        Http().singleRequest(HttpRequest(uri = uri))
          .flatMap { resp ⇒
            resp.entity.httpEntity.withSizeLimit(134217728L).dataBytes.runReduce(_ ++ _)
              .map { bs ⇒
                val jsonStr = bs.utf8String
                decode[immutable.Seq[GoogleRoutes]](jsonStr) match {
                  case Right(json) ⇒ json
                  case Left(e) ⇒
                    val head = jsonStr.take(200).replaceAll("\\s+", "")
                    logger.warn(s"Failed to parse $uri (<$head...>): ${e.getMessage}")
                    immutable.Seq.empty
                }
              }
          }
      }
      .mapAsync(1) { grsSeq: immutable.Seq[GoogleRoutes] ⇒
        Future.sequence(
          grsSeq.map { grs ⇒
            insertGoogleRoutes(dataSource, grs.routes)
              .map { seq: Seq[(json.GoogleRoute, Int)] ⇒
                seq.flatMap { case (gr, routeId) ⇒
                  gr.legs.map { leg ⇒
                    sql.Update.GoogleRouteLeg.fromJson(routeId, leg)
                  }
                }.toList
              }
          }
        ).map(_.flatten)
      }
      .flatMapConcat(Source(_))
      .grouped(1000)
      .viaMat(batchUpdateFlow)(Keep.right)
      .toMat(sink)(Keep.both)
      .run()

    val allDoneFuture = for {
      batchUpdateResult ← batchUpdateFuture
      _ ← Future { logger.info(
        "Routes legs: batchesProcessed={}, itemsProcessed={}, rowsUpdated={}",
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
    grs: Seq[json.GoogleRoute]
  )(implicit executor: ExecutionContext): Future[Seq[(json.GoogleRoute, Int)]] = Future({
    using(dataSource.getConnection) { con ⇒
      using(
        con.prepareStatement(
          sql.Update.GoogleRoute.insertSql,
          Statement.RETURN_GENERATED_KEYS
        )
      ) { ps ⇒
        grs.foreach { gr ⇒
          sql.Update.GoogleRoute.psMapping.mapPrepared(
            sql.Update.GoogleRoute.fromJson(gr),
            ps
          )
          ps.addBatch()
        }
        ps.executeBatch()

        val keysRS = ps.getGeneratedKeys

        grs.map { gr ⇒
          keysRS.next()
          (gr, keysRS.getInt(1))
        }
      }
    }
  })(executor)

  //
  // CLI Arguments
  //

  case class Arguments(
    jsonUrlsFile: Option[String] = None,
    pgUrl: Option[String] = None,
    pgUser: Option[String] = None,
    pgPass: Option[String] = None
  )

  private def parseArguments(parser: OptionParser[Arguments], args: Array[String]): Option[Arguments] = {
    parser.parse(args, init = Arguments())
  }

  private def parseArguments(args: Array[String]): Arguments =
    parseArguments(buildParser, args) match {
      case Some(pArgs) => pArgs
      case None =>
        throw new IllegalArgumentException(
          "Arguments provided were unable to be parsed. See above for reasoning."
        )
    }

  private def buildParser: OptionParser[Arguments] = {
    new scopt.OptionParser[Arguments]("GoogleRoutesDB") {
      opt[String]("json-urls-file")
        .action { (value, args) => args.copy(jsonUrlsFile = Option(value)) }
        .validate { value =>
          if (value.trim.isEmpty) {
            failure("Urls file path cannot be empty")
          } else success
        }
        .text("A location to file with urls")

      opt[String]("pg-url")
        .action { (value, args) => args.copy(pgUrl = Option(value)) }
        .validate { value =>
          if (value.trim.isEmpty) {
            failure("PG url cannot be empty")
          } else success
        }
        .text("PG url")

      opt[String]("pg-user")
        .action { (value, args) => args.copy(pgUser = Option(value)) }
        .validate { value =>
          if (value.trim.isEmpty) {
            failure("PG user cannot be empty")
          } else success
        }
        .text("PG user")

      opt[String]("pg-pass")
        .action { (value, args) => args.copy(pgPass = Option(value)) }
        .validate { value =>
          if (value.trim.isEmpty) {
            failure("PG pass cannot be empty")
          } else success
        }
        .text("PG pass")
    }
  }
}
