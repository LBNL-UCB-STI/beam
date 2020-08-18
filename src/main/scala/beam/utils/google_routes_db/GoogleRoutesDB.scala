package beam.utils.google_routes_db

import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.util.ByteString
import akka.{Done, NotUsed}
import beam.utils.google_routes_db.json._
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.decode
import org.apache.http.HttpHost
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.indices.{CreateIndexRequest, GetIndexRequest}
import org.elasticsearch.client.{RequestOptions, RestClient, RestHighLevelClient}
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

    val client = new RestHighLevelClient(
      RestClient.builder(
        new HttpHost("localhost", 9200, "http")
      )
    )

    client.indices().delete(new DeleteIndexRequest("google_route", "google_routes"), RequestOptions.DEFAULT)

    val uriSource: Source[Uri, NotUsed] =
      FileIO.fromPath(Paths.get(arguments.jsonUrlsFile.get))
        .via(Framing.delimiter(ByteString("\n"), 512))
        .map(_.utf8String)
        .map(Uri(_))
        .mapMaterializedValue(_ ⇒ NotUsed)

    val sink: Sink[Any, Future[Done]] = Sink.ignore  // just consume the steam

    val doneFuture = Source
      .future(Future {
        val indexExists = client.indices().exists(
          new GetIndexRequest(elasticsearch.googleRoutesIndex),
          RequestOptions.DEFAULT
        )
        if (!indexExists) {
          val req = new CreateIndexRequest(elasticsearch.googleRoutesIndex)
          req.mapping(elasticsearch.mapping)
          client.indices().create(req, RequestOptions.DEFAULT)
        }
      })
      .flatMapConcat { _ ⇒ uriSource }
      .mapAsync(2) { uri ⇒
        logger.info(s"Downloading $uri")
        Http().singleRequest(HttpRequest(uri = uri))
          .flatMap { resp ⇒
            resp.entity.httpEntity.withSizeLimit(134217728L).dataBytes.runReduce(_ ++ _)
              .map { bs ⇒
                val jsonStr = bs.utf8String
                decode[immutable.Seq[GoogleRoutes]](jsonStr) match {
                  case Right(googleRoutesArrayJson) ⇒
                    logger.info(
                      "{}: overall routes={}, overall legs={}",
                      uri,
                      googleRoutesArrayJson.map(_.routes.size).sum,
                      googleRoutesArrayJson.flatMap(_.routes).map(_.legs.size).sum
                    )
                    googleRoutesArrayJson
                  case Left(e) ⇒
                    val head = jsonStr.take(200).replaceAll("\\s+", "")
                    logger.warn(s"Failed to parse $uri (<$head...>): ${e.getMessage}")
                    immutable.Seq.empty
                }
              }
          }
          .map { grsSeq ⇒ (uri, grsSeq) }
      }
      .mapAsync(1) { case (uri, grsSeq) ⇒ Future {
        val uriStr = uri.toString()
        elasticsearch.googleRoutesToESMap(grsSeq, uriStr).foreach { doc ⇒
          val indxReq = new IndexRequest(elasticsearch.googleRoutesIndex)
          indxReq.id(UUID.randomUUID().toString)
          indxReq.source(doc)
          val resp = client.index(indxReq, RequestOptions.DEFAULT)
          logger.info(resp.toString)
        }
      }}
      .toMat(sink)(Keep.right)
      .run()

    val allDoneFuture = for {
      _ ← doneFuture
    } yield Done

    doneFuture.onComplete {
      case Success(_) ⇒
      case Failure(e) ⇒
        logger.error("An error occurred: {}", e.getMessage)
        e.printStackTrace()
    }

    Await.ready(allDoneFuture, 300.minutes)
    Await.ready(system.terminate(), 1.minute)

    System.exit(0)
  }

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
