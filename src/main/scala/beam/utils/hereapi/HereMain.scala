package beam.utils.hereapi

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils

object HereMain extends App {
  if (args.size != 3) {
    println("Expected arguments: [API-KEY] [ORIGIN] [DESTINATION]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096 37.724113,-122.447652")
    System.exit(1)
  }

  val result = FileUtils.using(new HereAdapter(args(0))) { adapter =>
    val pathFuture = adapter.findPath(
      origin = toWgsCoordinate(args(1)),
      destination = toWgsCoordinate(args(2))
    )
    Await.result(pathFuture, Duration("5 seconds"))
  }
  println(result)

  private def toWgsCoordinate(str: String) = {
    val tmp = str.split(",")
    WgsCoordinate(tmp(0).toDouble, tmp(1).toDouble)
  }

}
