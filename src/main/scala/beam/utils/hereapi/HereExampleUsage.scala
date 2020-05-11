package beam.utils.hereapi

import java.nio.file.{Files, Path, Paths}

import beam.agentsim.infrastructure.geozone.{GeoZoneUtil, WgsCoordinate}

object HereExampleUsage extends App {
  if (args.length != 3) {
    println("Expected arguments: [API-KEY] [ORIGIN] [DESTINATION]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096 37.724113,-122.447652")
    System.exit(1)
  }
  val apiKey = args(0)
  val originCoordinate = toWgsCoordinate(args(1))
  val destinationCoordinate = toWgsCoordinate(args(2))

  val result: Seq[HereSegment] = HereService.findSegments(apiKey, originCoordinate, destinationCoordinate)

  val allCoordinates = result.flatMap(_.coordinates).toSet
  val outputFile: Path = Paths.get("outputShapeFile.shx")
  GeoZoneUtil.writeToShapeFile(outputFile, allCoordinates, resolution = 12)
  println(s"Generated shape file: $outputFile")
  println(result.mkString(System.lineSeparator()))

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

}
