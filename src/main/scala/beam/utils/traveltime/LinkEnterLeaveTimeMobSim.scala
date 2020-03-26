package beam.utils.traveltime

import java.io.{BufferedWriter, File, FileWriter}
import java.util

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

import scala.collection.mutable

object LinkEnterLeaveTimeMobSim extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)

    val linkEnterEvents = mutable.Map[String, Event]() // personId, EnterEvent
    val file = new File("C:\\Users\\owner\\IdeaProjects\\beam\\output\\sf-light\\linkEnterLeaveTimes.csv")
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("linkId,enterTime,leaveTime\n")

    val eventHandler = new BasicEventHandler {
      override def handleEvent(event: Event): Unit = {
        val personId=event.getAttributes.get("person")
        val time=event.getAttributes.get("time")
        event.getEventType match {
          case "entered link" | "wait2link" => linkEnterEvents.put(personId,event)
          case "left link" | "vehicle leaves traffic" if linkEnterEvents.contains(personId)=>
            val linkId=event.getAttributes.get("link")
            val linkEnterTime=linkEnterEvents.get(personId).get.getAttributes.get("time")
            val linkLeaveTime=event.getAttributes.get("time")
            bw.write(s"$linkId,$linkEnterTime,$linkLeaveTime\n")
            linkEnterEvents.remove(personId)
          case _ =>
        }
      }
    }

    val networkLinks = initializeNetworkLinks(pathToNetworkXml)
    val eventsManager = EventsUtils.createEventsManager()
    eventsManager.addHandler(eventHandler)
    new MatsimEventsReader(eventsManager).readFile(pathToEventXml)
    bw.close()
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }
}
