package beam.utils.google_routes_db

import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.stream.scaladsl._
import beam.sim.BeamHelper
import beam.utils.csv.CsvWriter
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig
import beam.utils.google_routes_db.json._

import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object ReqRespMapping extends BeamHelper {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  def main(args: Array[String]): Unit = try {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val config = GoogleRoutesDBConfig(cfg)

    val epsilons = immutable.Seq(0.001, 0.0001, 0.00001)
    val maxSizeKey = 7

    // googleapiResponsesJsonFileUri,epsilon,maxSizeKey,reqReqSize=0,reqReqSize=1,...
    val header = ArrayBuffer("googleapiResponsesJsonFileUri", "epsilon", "maxSizeKey")
    header.appendAll(
      for {
        mapping ← Seq("reqReqSize", "reqRespSize", "respReqSize", "respRespSize")
        i ← 0 to maxSizeKey
      } yield {
        s"$mapping=$i"
      }
    )

    val csvWriter: CsvWriter = {
      new CsvWriter(s"ReqRespMapping.csv", header)
    }

    val doneFuture: Future[Done] = sourceGoogleapiFiles(config)
      .flatMapConcat { googleapiFiles ⇒
        Source(epsilons).map(epsilon ⇒ (googleapiFiles, epsilon))
      }
      .toMat(Sink.foreach { case (googleapiFiles, epsilon) ⇒
        val requests =
          parseGoogleTravelTimeEstimationCsv(
            googleapiFiles.googleTravelTimeEstimationCsvText
          )

        val grsSeq: immutable.Seq[json.GoogleRoutes] =
          parseGoogleapiResponsesJson(
            googleapiFiles.googleapiResponsesJsonText
          )

        val reqReqMapping: Map[Map[String, String], Seq[Map[String, String]]] =
          requests.map { request ⇒
            val nearby = requests.filter { other ⇒
                coordsNearby(
                  json.GoogleRoute.Coord(request("originLat").toDouble, request("originLng").toDouble),
                  json.GoogleRoute.Coord(other("originLat").toDouble, other("originLng").toDouble),
                  epsilon
                ) &&
                coordsNearby(
                  json.GoogleRoute.Coord(request("destLat").toDouble, request("destLng").toDouble),
                  json.GoogleRoute.Coord(other("destLat").toDouble, other("destLng").toDouble),
                  epsilon
                )
            }
            (request, nearby)
          }.toMap

        val reqRespMapping: Map[Map[String, String], Seq[GoogleRoute]] =
          requests.map { request ⇒
            val nearbyResponses = grsSeq.flatMap(_.routes).filter { route ⇒
                coordsNearby(
                  json.GoogleRoute.Coord(request("originLat").toDouble, request("originLng").toDouble),
                  route.legs.head.startLocation,
                  epsilon
                ) &&
                coordsNearby(
                  json.GoogleRoute.Coord(request("destLat").toDouble, request("destLng").toDouble),
                  route.legs.head.endLocation,
                  epsilon
                )
            }
            (request, nearbyResponses)
          }.toMap

        val respReqMapping: Map[GoogleRoute, Seq[Map[String, String]]] =
          grsSeq.flatMap(_.routes).map { route ⇒
            val nearbyRequests = requests.filter { request ⇒
                coordsNearby(
                  json.GoogleRoute.Coord(
                    request("originLat").toDouble,
                    request("originLng").toDouble
                  ),
                  route.legs.head.startLocation,
                  epsilon
                ) &&
                coordsNearby(
                  json.GoogleRoute.Coord(
                    request("destLat").toDouble,
                    request("destLng").toDouble
                  ),
                  route.legs.head.endLocation,
                  epsilon
                )
            }
            (route, nearbyRequests)
          }.toMap

        val respRespMapping: Map[GoogleRoute, Seq[GoogleRoute]] =
          grsSeq.flatMap(_.routes).map { route ⇒
            val nearby = grsSeq.flatMap(_.routes).filter { other ⇒
                coordsNearby(
                  route.legs.head.startLocation,
                  other.legs.head.startLocation,
                  epsilon
                ) &&
                coordsNearby(
                  route.legs.head.endLocation,
                  other.legs.head.endLocation,
                  epsilon
                )
            }
            (route, nearby)
          }.toMap

        val reqReqMappingSizeFreq = getSizeFrequencies(reqReqMapping)
        val reqRespMappingSizeFreq = getSizeFrequencies(reqRespMapping)
        val respReqMappingSizeFreq = getSizeFrequencies(respReqMapping)
        val respRespMappingSizeFreq = getSizeFrequencies(respRespMapping)

        val rrmsfs = Seq(
          reqReqMappingSizeFreq,
          reqRespMappingSizeFreq,
          respReqMappingSizeFreq,
          reqRespMappingSizeFreq
        )

        val keySet: Set[Int] = rrmsfs.map(_.keySet).reduce(_ ++ _)

        val row = mutable.ArrayBuffer[Any]()
        row += googleapiFiles.googleapiResponsesJsonFileUri
        row += epsilon
        row += keySet.max
        for (rrmsf ← rrmsfs) {
          row ++= (0 to maxSizeKey).map(rrmsf.getOrElse(_, 0))
        }

        csvWriter.writeRow(row)
      })(Keep.right)
      .run()

    doneFuture.onComplete {
      case Success(_) ⇒
        csvWriter.flush()
        csvWriter.close()
      case Failure(e) ⇒
        logger.error("An error occurred: {}", e.getMessage)
        e.printStackTrace()
        csvWriter.close()
    }

    Await.ready(doneFuture, 1.minute)
    Await.ready(system.terminate(), 1.minute)
    System.exit(0)
  } catch {
    case e: Throwable ⇒
      e.printStackTrace()

      Await.ready(system.terminate(), 1.minute)
      System.exit(1)
  }
}
