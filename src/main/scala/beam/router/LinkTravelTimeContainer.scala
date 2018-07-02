package beam.router

import java.io.{BufferedReader, FileInputStream, InputStreamReader}
import java.util
import java.util.zip.GZIPInputStream

import beam.router.LinkTravelTimeContainer.LinkTravelTime
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle


class LinkTravelTimeContainer(fileName: String,
                              timeBinSizeInSeconds: Int) extends TravelTime with LazyLogging {

  var linkTravelTimeMap: util.HashMap[Id[Link], LinkTravelTime] = new util.HashMap()



  def loadLinkStats(): Unit = {
    logger.debug(s"Stats fileName -> $fileName is being loaded")

    val gzipStream = new GZIPInputStream(new FileInputStream(fileName))
    val bufferedReader = new BufferedReader( new InputStreamReader(gzipStream))

    var line: String = null

    while ({line = bufferedReader.readLine; line != null}){
      val linkStats = line.split(",")

      if(linkStats.length == 10) {
        val linkId = linkStats(0)
        val hour = linkStats(3)
        val travelTime = linkStats(9)
        val stat = linkStats(7)

        if (stat.equalsIgnoreCase("avg")) {
          val _linkId = Id.createLinkId(linkId)
          if (linkTravelTimeMap.containsKey(_linkId)) {

            val linkTravelTime = linkTravelTimeMap.get(_linkId)
            linkTravelTime.map.put(hour.toDouble.toInt, travelTime.toDouble)
            linkTravelTimeMap.put(_linkId, linkTravelTime)
          } else {

            val map = new util.HashMap[Int, Double]()
            map.put(hour.toDouble.toInt, travelTime.toDouble)

            val linkTravelTime = LinkTravelTime(_linkId, map)
            linkTravelTimeMap.put(_linkId, linkTravelTime)
          }
        }
      }
    }

    logger.debug("LinkTravelTimeMap is initialized")
  }


  def getLinkTravelTime(link: Link, time: Double, person: Person, vehicle: Vehicle): Double = {
    if (linkTravelTimeMap.keySet().contains(link.getId)){
      if (linkTravelTimeMap.get(link.getId).map.containsKey(getSlot(time))){

        linkTravelTimeMap.get(link.getId).map.get(getSlot(time))
      } else {
        link.getFreespeed
      }
    } else {
      link.getFreespeed
    }
  }

  def getSlot(time: Double): Int ={

    Math.round(Math.floor(time/timeBinSizeInSeconds)).toInt
  }

  loadLinkStats()
}

object LinkTravelTimeContainer {
  // map is a map from slotId -> TraveTime
  case class LinkTravelTime(link: Id[Link], map: util.HashMap[Int, Double])
}
