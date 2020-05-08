package beam.utils.hereapi

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils

object HereMain extends App {
  if (args.length != 3) {
    println("Expected arguments: [API-KEY] [ORIGIN] [DESTINATION]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096 37.724113,-122.447652")
    System.exit(1)
  }
  val originCoordinate = toWgsCoordinate(args(1))
  val destinationCoordinate = toWgsCoordinate(args(2))
  println(originCoordinate, destinationCoordinate)

  val result = FileUtils.using(new HereAdapter(args(0))) { adapter =>
    val service = new HereService(adapter)
    val segFuture = service.findSegments(origin = originCoordinate, destination = destinationCoordinate)
    Await.result(segFuture, Duration("5 seconds"))
  }

  println(result.mkString(System.lineSeparator()))

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

}
