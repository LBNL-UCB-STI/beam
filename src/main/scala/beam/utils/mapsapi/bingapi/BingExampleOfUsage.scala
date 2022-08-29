package beam.utils.mapsapi.bingapi

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.utils.FileUtils

object BingExampleOfUsage extends App {
  if (args.length != 2) {
    println("Expected arguments: [API-KEY] [lat1,long1;lat2,long2;lat3,long3]")
    println("Example: KqkuBonCHeDLytZwdGfKcUH9N287H-lOdqu 37.705687,-122.461096;37.724113,-122.447652")
    System.exit(1)
  }

  private val timeout = FiniteDuration(20, TimeUnit.SECONDS)
  private val apiKey = args(0)

  private val points: Array[WgsCoordinate] = args(1).split(";").flatMap { latLong =>
    Try {
      val elements = latLong.split(",")
      val lat = elements(0).toDouble
      val long = elements(1).toDouble
      WgsCoordinate(lat, long)
    }.toOption
  }

  FileUtils.using(new BingAdapter(apiKey)) { bingAdapter =>
    val result = Await.result(bingAdapter.findPath(points), timeout)
    println(result)
  }

}
