package beam.utils.hereapi

import java.nio.file.{Path, Paths}

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.hereapi.RichSegments._

object HereExampleUsage extends App {
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
  val result: Seq[HereSegment] = HereService.findSegments(apiKey, originCoordinate, destinationCoordinate)
  result
    .saveToShapeFile(outputShapeFile)
    .saveToCsv(outputCsvFile)

  val result2: Seq[HereSegment] = HereService.fromCsv(outputCsvFile)

  println(s"Generated shape file: $outputShapeFile")
  println(s"Generated csv file: $outputCsvFile")
  println(s"Content written at [$outputCsvFile] is the same read from file: ${result == result2}")

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

}
