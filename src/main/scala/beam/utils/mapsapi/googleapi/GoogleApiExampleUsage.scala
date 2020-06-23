package beam.utils.mapsapi.googleapi

import java.nio.file.{Path, Paths}
import java.time.LocalDateTime

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import beam.utils.mapsapi.RichSegments._
import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils

object GoogleApiExampleUsage extends App {
  if (args.length != 3) {
    println("Expected arguments: [API-KEY] [ORIGIN] [DESTINATION]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096 37.724113,-122.447652")
    System.exit(1)
  }
  val apiKey = args(0)
  val originCoordinate = toWgsCoordinate(args(1))
  val destinationCoordinate = toWgsCoordinate(args(2))

  // You can build the URL yourself
  val url = GoogleAdapter.buildUrl(
    apiKey = apiKey,
    origin = originCoordinate,
    destination = destinationCoordinate,
    departureAt = LocalDateTime.of(2020, 6, 5, 17, 20),
    mode = TravelModes.Driving,
    constraints = Set.empty
  )

  val outputJson: Path = Paths.get("outputJson.json")
  val routes = findRoutesAndWriteJson(Some(outputJson))
  println(s"Generated json file: $outputJson")

  val outputShapeFile: Path = Paths.get("outputShapeFile.shx")
  val outputCsvFile: Path = Paths.get("outputSegments.csv")
  routes.head.segments
    .saveToCsv(outputCsvFile)
    .saveToShapeFile(outputShapeFile)
  println(s"Generated shape file: $outputShapeFile")
  println(s"Generated csv file: $outputCsvFile")

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

  private def findRoutesAndWriteJson(outputJson: Option[Path]): Seq[Route] = {
    FileUtils.using(new GoogleAdapter(apiKey, outputJson)) { adapter =>
      val eventualRoutes = adapter.findRoutes(
        origin = originCoordinate,
        destination = destinationCoordinate,
        departureAt = LocalDateTime.of(2020, 6, 5, 17, 20),
        mode = TravelModes.Driving,
        trafficModel = TrafficModels.BestGuess,
        constraints = Set.empty
      )
      Await.result(eventualRoutes, Duration.Inf)
    }
  }

}
