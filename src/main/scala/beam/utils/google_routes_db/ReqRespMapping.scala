package beam.utils.google_routes_db

import java.util.concurrent.Executors

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.common.{EntityStreamingSupport, JsonEntityStreamingSupport}
import akka.stream.scaladsl._
import beam.sim.BeamHelper
import beam.sim.common.SimpleGeoUtils
import beam.utils.csv.CsvWriter
import beam.utils.google_routes_db.config.GoogleRoutesDBConfig
import beam.utils.google_routes_db.{request => req, response => resp}
import org.matsim.api.core.{v01 => matsim}

import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Output file: ReqRespMapping.csv
 *
 * Run with Gradle:
 * ./gradlew :execute \
 *   -PmainClass=beam.utils.google_routes_db.ReqRespMapping \
 *   -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/config/google_routes_db.conf']" \
 *   -PlogbackCfg=logback.xml
 */
object ReqRespMapping extends BeamHelper {

  private implicit val system: ActorSystem = ActorSystem("google-routes-db")
  private implicit val execCtx: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

  private var csvWriter: CsvWriter = _

  //noinspection DuplicatedCode
  def main(args: Array[String]): Unit = try {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)
    val config = GoogleRoutesDBConfig(cfg)
    val geoUtils = SimpleGeoUtils(localCRS = config.spatial.localCRS)

    val epsilons = immutable.Seq(0.001, 0.0001, 0.00001)
    val maxSizeKey = 7

    // googleapiResponsesJsonFileUri,epsilon,maxSizeKey,reqReqSize=0,reqReqSize=1,...
    val header = ArrayBuffer("googleapiResponsesJsonFileUri", "epsilon", "maxSizeKey")
    header.appendAll(
      for {
        mapping <- Seq("reqReqSize", "reqRespSize", "respReqSize", "respRespSize")
        i <- 0 to maxSizeKey
      } yield {
        s"$mapping=$i"
      }
    )

    csvWriter = new CsvWriter(s"ReqRespMapping.csv", header)

    val doneFuture: Future[Done] = sourceGoogleapiFiles(config)
      .flatMapConcat { googleapiFiles =>
        Source(epsilons).map(epsilon => (googleapiFiles, epsilon))
      }
      .toMat(Sink.foreach { case (googleapiFiles, epsilon) =>
        val requests =
          req.csv.parseGoogleTravelTimeEstimationCsv(
            googleapiFiles.googleTravelTimeEstimationCsvText
          )

        val grsSeq: immutable.Seq[resp.GoogleRoutesResponse] =
          resp.json.parseGoogleapiResponsesJson(
            googleapiFiles.googleapiResponsesJsonText
          )

        val reqReqMapping: Map[req.GoogleRouteRequest, Seq[req.GoogleRouteRequest]] =
          requests.map { request =>
            val nearbyRequests = requests.filter { otherRequest =>

              val originDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.originLat, request.originLng),
                new matsim.Coord(otherRequest.originLat, otherRequest.originLng)
              )
              val destinationDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.destLat, request.destLng),
                new matsim.Coord(otherRequest.destLat, otherRequest.destLng)
              )

              originDifference < epsilon && destinationDifference < epsilon
            }
            (request, nearbyRequests)
          }.toMap

        val reqRespMapping: Map[req.GoogleRouteRequest, Seq[resp.GoogleRoute]] =
          requests.map { request =>
            val nearbyResponses = grsSeq.flatMap(_.response.routes).filter { route =>
              val leg = route.legs.head  // data holds 1 leg per route

              val originDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.originLat, request.originLng),
                new matsim.Coord(leg.startLocation.lat, leg.startLocation.lng)
              )
              val destinationDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.destLat, request.destLng),
                new matsim.Coord(leg.endLocation.lat, leg.endLocation.lng)
              )

              originDifference < epsilon && destinationDifference < epsilon
            }
            (request, nearbyResponses)
          }.toMap

        val respReqMapping: Map[resp.GoogleRoute, Seq[req.GoogleRouteRequest]] =
          grsSeq.flatMap(_.response.routes).map { route =>
            val nearbyRequests = requests.filter { request =>
              val leg = route.legs.head  // data holds 1 leg per route

              val originDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.originLat, request.originLng),
                new matsim.Coord(leg.startLocation.lat, leg.startLocation.lng)
              )
              val destinationDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(request.destLat, request.destLng),
                new matsim.Coord(leg.endLocation.lat, leg.endLocation.lng)
              )

              originDifference < epsilon && destinationDifference < epsilon
            }
            (route, nearbyRequests)
          }.toMap

        val respRespMapping: Map[resp.GoogleRoute, Seq[resp.GoogleRoute]] =
          grsSeq.flatMap(_.response.routes).map { route =>
            val nearby = grsSeq.flatMap(_.response.routes).filter { otherRoute =>
              val leg = route.legs.head
              val otherLeg = otherRoute.legs.head

              val originDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(leg.startLocation.lat, leg.startLocation.lng),
                new matsim.Coord(otherLeg.startLocation.lat, otherLeg.startLocation.lng)
              )
              val destinationDifference = geoUtils.distLatLon2Meters(
                new matsim.Coord(leg.endLocation.lat, leg.endLocation.lng),
                new matsim.Coord(otherLeg.endLocation.lat, otherLeg.endLocation.lng)
              )

              originDifference < epsilon && destinationDifference < epsilon
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
          respRespMappingSizeFreq
        )

        val keySet: Set[Int] = rrmsfs.map(_.keySet).reduce(_ ++ _)

        val row = mutable.ArrayBuffer[Any]()
        row += googleapiFiles.googleapiResponsesJsonFileUri
        row += epsilon
        row += keySet.max
        for (rrmsf <- rrmsfs) {
          row ++= (0 to maxSizeKey).map(rrmsf.getOrElse(_, 0))
        }

        csvWriter.writeRow(row)
      })(Keep.right)
      .run()

    doneFuture.onComplete {
      case Success(_) => closeCsvWriter()
      case Failure(e) =>
        logger.error("An error occurred: {}", e.getMessage)
        e.printStackTrace()
        closeCsvWriter()
    }

    Await.ready(doneFuture, 300.minutes)
    Await.ready(system.terminate(), 1.minute)
    System.exit(0)
  } catch {
    case e: Throwable =>
      e.printStackTrace()

      closeCsvWriter()
      Await.ready(system.terminate(), 1.minute)
      System.exit(1)
  }

  private def closeCsvWriter(): Unit = {
    try {
      Option(csvWriter).foreach(_.flush())
    } catch {
      case e: Throwable =>
        logger.warn("Failed to flush csvWriter: {}", e.getMessage)
    }
    try {
      Option(csvWriter).foreach(_.close())
    } catch {
      case e: Throwable =>
        logger.warn("Failed to close csvWriter: {}", e.getMessage)
    }
  }
}
