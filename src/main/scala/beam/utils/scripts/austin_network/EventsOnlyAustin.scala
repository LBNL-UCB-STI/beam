package beam.utils.scripts.austin_network

import java.io.{File, PrintWriter}

import beam.sim.common.GeoUtils
import beam.utils.EventReplayer
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source

object EventsOnlyAustin {

  val geoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }


  def getLinksAustin(network: Network, wsgCoordCornerA: Coord, wsgCoordCornerB: Coord): mutable.HashSet[Link] = {
    var austinLinks = mutable.HashSet[Link]()
    val minX = Math.min(wsgCoordCornerA.getX, wsgCoordCornerB.getX)
    val maxX = Math.max(wsgCoordCornerA.getX, wsgCoordCornerB.getX)
    val minY = Math.min(wsgCoordCornerA.getY, wsgCoordCornerB.getY)
    val maxY = Math.max(wsgCoordCornerA.getY, wsgCoordCornerB.getY)

    network.getLinks.asScala.toVector.foreach {
      case (_, link) =>
        val wsgCoord = geoUtils.utm2Wgs(link.getCoord)
        if (wsgCoord.getX > minX && wsgCoord.getX < maxX && wsgCoord.getY > minY && wsgCoord.getY < maxY) {
          austinLinks += link
        }
    }

    austinLinks
  }

  def main(args: Array[String]): Unit = {
import AustinUtils._

    val networkPath="C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-25_07-21-44_tmc\\output_network.xml.gz"
    val eventsPath=getFileCachePath("https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-with-sa-flowCap0.17-speedScalingFactor0.75-overwrite-link-params-pre-final__2020-04-26_14-15-58_yem/ITERS/it.20/20.physSimEvents.xml.gz")
    val volumesOutputPath="E:\\work\\scalaFileCache\\20.volumesPerHour1.csv"

    val network = AustinUtils.getPhysSimNetwork(networkPath)

    val linkIdsOfTrafficDetectors = MapPhysSimToTrafficDetectors.getPhysSimNetworkIdsWithTrafficDectors(5, network).toSet

    //val wsgCoordCornerA = new Coord(-97.846119, 30.236527)
    //val wsgCoordCornerB = new Coord(-97.585019, 30.468798)

    //val austinLinks: mutable.HashSet[Link] = getLinksAustin(network, wsgCoordCornerA, wsgCoordCornerB)

    val events: IndexedSeq[Event] = EventReplayer.readEvents(eventsPath)
    println(events.size)
    val filteredEvents = events.filter { event =>
      (event.getEventType == "entered link" || event.getEventType == "wait2link") && linkIdsOfTrafficDetectors.contains(event.getAttributes.get("link"))
    }.map(event => (event.getTime.toInt / 3600, 1))
    val maxHour = filteredEvents.map(_._1).max
    val ensureAllHoursHaveValues = (filteredEvents ++ (0 to maxHour).map(hour => (hour, 0))).groupBy { case (hour, freq) =>
      hour
    }.map { case (hour, volume) =>
      (hour, volume.map(_._2).sum)
    }
    val updatedEvents = ensureAllHoursHaveValues.toVector.sortBy(key => key._1)

    var pw = new PrintWriter(new File(volumesOutputPath))
    pw.write(s"hour,volume\n")

    updatedEvents.foreach { case (hour, volume) =>
      pw.write(s"$hour,$volume\n")
    }
    pw.close()
  }

}
