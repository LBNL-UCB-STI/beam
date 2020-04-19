package beam.utils.scripts.austin_network

import java.io.{File, PrintWriter}

import beam.sim.common.GeoUtils
import beam.utils.EventReplayer
import org.matsim.api.core.v01.Coord
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

    val network = AustinNetworkSpeedMatching.getNetwork("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-18_12-41-18_obj\\output_network.xml.gz")

    val wsgCoordCornerA = new Coord(-97.846119, 30.236527)
    val wsgCoordCornerB = new Coord(-97.585019, 30.468798)

    val austinLinks: mutable.HashSet[Link] = getLinksAustin(network, wsgCoordCornerA, wsgCoordCornerB)

    val events: IndexedSeq[Event] = EventReplayer.readEvents("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-18_12-41-18_obj\\ITERS\\it.20\\20.physSimEvents.xml.gz")
    println(events.size)
    val filteredEvents=events.filter { event =>
      event.getEventType == "entered link" || event.getEventType == "wait2link"
    }.map(_.getTime.toInt/3600).groupBy(a => a).map(key => (key._1,key._2.size)).toVector.sortBy(key => key._1 )

    var pw = new PrintWriter(new File("C:\\Users\\owner\\IdeaProjects\\beam\\output\\austin\\austin-prod-1k-activities__2020-04-18_12-41-18_obj\\ITERS\\it.20\\20.volumesPerHour.csv"))
    pw.write(s"hour,volume\n")

    filteredEvents.foreach { case (hour,volume) =>
      pw.write(s"$hour,$volume\n")
    }
    pw.close()
  }

}
