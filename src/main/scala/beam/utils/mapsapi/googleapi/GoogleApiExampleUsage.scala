package beam.utils.mapsapi.googleapi

import java.nio.file.{Path, Paths}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.mapsapi.RichSegments._
import beam.utils.mapsapi.{Segment, TransitPath}
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

  val outputShapeFile: Path = Paths.get("outputShapeFile.shx")
  val outputCsvFile: Path = Paths.get("outputSegments.csv")

  val result: Seq[Segment] = findSegments().segments
  result
    .saveToShapeFile(outputShapeFile)
    .saveToCsv(outputCsvFile)

  println(s"Generated shape file: $outputShapeFile")
  println(s"Generated csv file: $outputCsvFile")

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

  private def findSegments(): TransitPath = {
    FileUtils.using(new GoogleAdapter(apiKey)) { adapter =>
      Await.result(
        adapter.findPath(originCoordinate, destinationCoordinate),
        Duration.Inf
      )
    }
  }

}
