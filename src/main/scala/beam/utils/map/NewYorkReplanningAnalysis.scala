package beam.utils.map

import java.util.UUID

import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.sim.common.GeoUtils
import beam.utils.EventReader
import beam.utils.shape.{Attributes, ShapeWriter}
import com.vividsolutions.jts.geom._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.mutable

private case class Attribute(count: Int) extends Attributes

object NewYorkReplanningAnalysis {
  private val geometryFactory = new GeometryFactory()

  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    override def localCRS: String = "epsg:32118"
  }

  def readNetwork(path: String): Network = {
    val n = NetworkUtils.createNetwork()
    new MatsimNetworkReader(n)
      .readFile(path)
    n
  }

  def main(args: Array[String]): Unit = {
    val pathToNetwork =
      "D:/Work/beam/NewYork/Runs/new-york-200k-baseline-crowding-1__2020-08-18_22-10-47_tra/outputNetwork.xml.gz"
    val pathToEvents =
      "D:/Work/beam/NewYork/Runs/new-york-200k-baseline-crowding-1__2020-08-18_22-10-47_tra/0.events.csv.gz"

    val network = readNetwork(pathToNetwork)

    val events = {
      val (it, toClose) =
        EventReader.fromCsvFile(pathToEvents, ev => Set("Replanning", "ModeChoice").contains(ev.getEventType))
      val xs = it.map { ev =>
        EventReader.fixEvent(ev)
      }.toArray
      toClose.close()
      xs
    }
    val replannings = events.collect {
      case ev: ReplanningEvent => ev
    }
    val reasonToEvents = replannings.groupBy(x => x.getReason)
    reasonToEvents.foreach {
      case (reason, xs) =>
        println(s"$reason: ${xs.length}")
    }

    val timeToEvents = events.groupBy(x => x.getTime.toInt).map {
      case (time, xs) =>
        (time, xs.collect { case x: ModeChoiceEvent => x })
    }
    println(s"events: ${events.length}")

    val interestedReasons = Set("ResourceCapacityExhausted WALK_TRANSIT", "MissedTransitPickup DRIVE_TRANSIT")

    interestedReasons.foreach { reason =>
      val locationToNumberOfExhaustion = mutable.Map.empty[String, Int]

      val relatedReplannings: Array[ReplanningEvent] = reasonToEvents(reason)
      relatedReplannings.foreach { replanning =>
        val modeChoiceEvents = timeToEvents(replanning.getTime.toInt)
        val replatedPersonModeChoices =
          modeChoiceEvents.filter(x => x.personId.toString == replanning.getPersonId.toString)
        replatedPersonModeChoices.foreach { mc =>
          val updated = locationToNumberOfExhaustion.getOrElse(mc.location, 0) + 1
          locationToNumberOfExhaustion.put(mc.location, updated)
        }
      }
      val shapeWriter = ShapeWriter.worldGeodetic[Point, Attribute](s"${reason}.shp")
      locationToNumberOfExhaustion.foreach {
        case (location, cnt) =>
          val link = network.getLinks.get(Id.createLinkId(location))
          val wgsCoord = geoUtils.utm2Wgs(link.getCoord)
          val point = geometryFactory.createPoint(new Coordinate(wgsCoord.getX, wgsCoord.getY))
          shapeWriter.add(point, UUID.randomUUID().toString, Attribute(cnt))
      }
      shapeWriter.write()
    }
  }
}
