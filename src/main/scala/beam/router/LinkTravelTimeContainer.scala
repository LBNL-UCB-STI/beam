package beam.router

import java.io.{BufferedReader, FileInputStream, InputStreamReader}
import java.util.zip.GZIPInputStream

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

class LinkTravelTimeContainer(fileName: String, timeBinSizeInSeconds: Int, maxHour: Int)
    extends TravelTime
    with LazyLogging {
  val linkTravelTimeMap: scala.collection.Map[Id[Link], Array[Double]] = loadLinkStats()

  def loadLinkStats(): scala.collection.Map[Id[Link], Array[Double]] = {
    val start = System.currentTimeMillis()
    val linkTravelTimeMap: mutable.HashMap[Id[Link], Array[Double]] = mutable.HashMap()
    logger.debug(s"Stats fileName -> $fileName is being loaded")

    val gzipStream = new GZIPInputStream(new FileInputStream(fileName))
    val bufferedReader = new BufferedReader(new InputStreamReader(gzipStream))
    try {
      var line: String = null
      while ({
        line = bufferedReader.readLine
        line != null
      }) {
        val linkStats = line.split(",")
        if (linkStats.length == 10 && "avg".equalsIgnoreCase(linkStats(7))) {
          val linkId = Id.createLinkId(linkStats(0))
          val hour = linkStats(3).toDouble.toInt
          val travelTime = linkStats(9).toDouble
          linkTravelTimeMap.get(linkId) match {
            case Some(travelTimePerHourArr) =>
              travelTimePerHourArr.update(hour, travelTime)
            case None =>
              val travelTimePerHourArr = Array.ofDim[Double](maxHour)
              travelTimePerHourArr.update(hour, travelTime)
              linkTravelTimeMap.put(linkId, travelTimePerHourArr)
          }
        }
      }
    } finally {
      bufferedReader.close()
      gzipStream.close()
    }
    val end = System.currentTimeMillis()
    logger.info("LinkTravelTimeMap is initialized in {} ms", end - start)

    linkTravelTimeMap
  }

  def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double = {
    linkTravelTimeMap.get(link.getId) match {
      case Some(traveTimePerHour) =>
        val idx = getSlot(time)
        if (idx < traveTimePerHour.size) traveTimePerHour(idx)
        else {
          logger.warn("Got {} as index for traveTimePerHour with max size {}. Something might be wrong!", idx, maxHour)
          link.getFreespeed
        }
      case None =>
        link.getFreespeed
    }
  }

  private def getSlot(time: Double): Int = {
    Math.round(Math.floor(time / timeBinSizeInSeconds)).toInt
  }
}
