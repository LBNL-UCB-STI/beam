package beam.router

import java.io.{BufferedReader, FileInputStream, InputStreamReader}
import java.util.zip.GZIPInputStream

import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

class LinkTravelTimeContainer(fileName: String, timeBinSizeInSeconds: Int) extends TravelTime with LazyLogging {

  private var linkTravelTimeMap: Map[Id[Link], Map[Int, Double]] = Map()

  private def loadLinkStats(): Unit = {
    logger.debug(s"Stats fileName -> $fileName is being loaded")

    val gzipStream = new GZIPInputStream(new FileInputStream(fileName))
    val bufferedReader = new BufferedReader(new InputStreamReader(gzipStream))
//    Source.fromInputStream(gzipStream).getLines()
//      .map(_.split(",")).filter(_ (7).equalsIgnoreCase("avg")).foreach(linkStats => {
//
//    })

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

        val travelTimes = linkTravelTimeMap.get(linkId) match {
          case Some(linkTravelTime) =>
            linkTravelTime + (hour -> travelTime)
          case None =>
            Map(hour -> travelTime)
        }
        linkTravelTimeMap += (linkId -> travelTimes)
      }
    }

    logger.debug("LinkTravelTimeMap is initialized")
  }

  def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double = {
    linkTravelTimeMap.get(link.getId) match {
      case Some(linkTravelTime) =>
        linkTravelTime.get(getSlot(time)) match {
          case Some(travelTime) =>
            travelTime
          case None =>
            link.getFreespeed
        }
      case None =>
        link.getFreespeed
    }
  }

  private def getSlot(time: Double): Int = {

    Math.round(Math.floor(time / timeBinSizeInSeconds)).toInt
  }

  loadLinkStats()
}
