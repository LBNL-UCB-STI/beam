package beam.utils.mapsapi.googleapi

import java.nio.file.{Path, Paths}
import java.time.LocalDateTime
import java.util.concurrent.Executors

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import beam.utils.mapsapi.RichSegments._
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils
import beam.utils.mapsapi.Segment
import beam.utils.mapsapi.googleapi.GoogleAdapter.FindRouteRequest
import beam.utils.mapsapi.googleapi.route.GoogleRoutesResponse

object GoogleApiExampleUsage extends App {
  if (args.length != 3) {
    println("Expected arguments: [API-KEY] [ORIGIN] [DESTINATION]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096 37.724113,-122.447652")
    System.exit(1)
  }
  val apiKey = args(0)
  val originCoordinate = toWgsCoordinate(args(1))
  val destinationCoordinate = toWgsCoordinate(args(2))

  private implicit val execCtx: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  // You can build the URL yourself
  val url = GoogleAdapter.buildUrl(
    apiKey = apiKey,
    request = FindRouteRequest(
      userObject = "dummy",
      origin = originCoordinate,
      destination = destinationCoordinate,
      departureAt = LocalDateTime.of(2020, 6, 5, 17, 20)
    )
  )

  val outputJson: Path = Paths.get("outputJson.json")
  val resps = findRoutesAndWriteJson(Some(outputJson))
  println(s"Generated json file: $outputJson")

  val outputShapeFile: Path = Paths.get("outputShapeFile.shx")
  val outputCsvFile: Path = Paths.get("outputSegments.csv")
  resps
    .flatMap { resp =>
      resp.response.routes.flatMap { route =>
        route.legs.flatMap { leg =>
          leg.steps.map { step =>
            Segment(
              coordinates = GooglePolylineDecoder.decode(step.polyline.points),
              lengthInMeters = leg.distance.value,
              durationInSeconds = Some(leg.duration.value)
            )
          }
        }
      }
    }
    .saveToCsv(outputCsvFile)
    .saveToShapeFile(outputShapeFile)
  println(s"Generated shape file: $outputShapeFile")
  println(s"Generated csv file: $outputCsvFile")

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

  private def findRoutesAndWriteJson(outputJson: Option[Path]): Seq[GoogleRoutesResponse] = {
    FileUtils.using(new GoogleAdapter(apiKey, outputJson)) { adapter =>
      val eventualRoutes = adapter
        .findRoutes(Seq(
          FindRouteRequest(
            userObject = "dummy",
            origin = originCoordinate,
            destination = destinationCoordinate,
            departureAt = LocalDateTime.of(2020, 9, 17, 17, 20)
          )
        ))
        .map(_.map(_.eitherResp).flatMap {
          case Right(resp) => Seq(resp)
          case Left(_) => Seq.empty
        })

      Await.result(eventualRoutes, Duration.Inf)
    }
  }

}
