package beam.utils

import java.time.temporal.ChronoUnit

import beam.router.BeamRouter.RoutingResponse
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object RoutingRequestResponseStats extends LazyLogging{
  private val fullTimes: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]
  private val requestTravelTimes: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]
  private val responseTravelTimes: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]
  private val routeCalcTimes: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty[Double]

  def add(resp: RoutingResponse): Unit = {
    return;
    val fullTime = ChronoUnit.MILLIS.between(resp.requestCreatedAt, resp.receivedAt.get)
    fullTimes.synchronized {
      fullTimes += fullTime
    }

    val requestTravelTime = ChronoUnit.MILLIS.between(resp.requestCreatedAt, resp.requestReceivedAt)
    requestTravelTimes.synchronized {
      requestTravelTimes += requestTravelTime
    }

    val responseTravelTime = ChronoUnit.MILLIS.between(resp.createdAt, resp.receivedAt.get)
    responseTravelTimes.synchronized {
      responseTravelTimes += responseTravelTime
    }

    routeCalcTimes.synchronized {
      routeCalcTimes += resp.routeCalcTimeMs
    }
  }

  def fullTimeStat: Statistics = {
    fullTimes.synchronized {
      Statistics(fullTimes)
    }
  }

  def requestTravelTimeStat: Statistics = {
    requestTravelTimes.synchronized {
      Statistics(requestTravelTimes)
    }
  }

  def responseTravelTimeStat: Statistics = {
    responseTravelTimes.synchronized {
      Statistics(responseTravelTimes)
    }
  }

  def routeCalcTime: Statistics = {
    routeCalcTimes.synchronized {
      Statistics(routeCalcTimes)
    }
  }
}
