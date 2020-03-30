package beam.utils.traveltime

import java.io.{BufferedWriter, File, FileWriter}
import java.util

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

import scala.collection.mutable

object LinkEnterLeaveTimeMobSim extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)
    val pathToNetworkXml = args(1)


//
    val eventHandler = new LinkEnterLeaveTimeMobSimHandler("C:\\Users\\owner\\IdeaProjects\\beam\\output\\sf-light\\linkEnterLeaveTimes.csv")

    val networkLinks = initializeNetworkLinks(pathToNetworkXml)
    val eventsManager = EventsUtils.createEventsManager()
    eventsManager.addHandler(eventHandler)
    new MatsimEventsReader(eventsManager).readFile(pathToEventXml)
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }

  class LinkEnterLeaveTimeMobSimHandler(outputFileName: String) extends BasicEventHandler {
    val linkEnterEvents = mutable.Map[String, Event]() // personId, EnterEvent
    val wait2LinkEvents = mutable.Map[String, Event]() // personId, Wait2Link
    val departure = mutable.Map[String, Event]() // personId, Departure
    val file = new File(outputFileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("linkId,enterTime,leaveTime,enterType,departureTime\n")

    override def reset(iteration: Int): Unit = {
      bw.close()
    }

    def handleEvent(event: Event): Unit = {
      val personId=event.getAttributes.getOrDefault("person",event.getAttributes.get("vehicle"))
      if (personId==null){
        logger.warn(s"personId is null")
      }
      val time=event.getAttributes.get("time")
      logger.info(event.toString)
      wait2LinkEvents.size>0

      event.getEventType match {
        case "departure" => departure.put(personId,event)
        case "entered link" => linkEnterEvents.put(personId,event)
        case  "vehicle enters traffic" => wait2LinkEvents.put(personId,event)
        case "left link" | "vehicle leaves traffic" =>
          val linkId=event.getAttributes.get("link")
          val linkLeaveTime=event.getAttributes.get("time")



          val (linkEnterTime,enterType,departureTime) = if (linkEnterEvents.contains(personId)) {
            (Some(linkEnterEvents.remove(personId).get.getAttributes.get("time")),"linkEnter",None)
          } else if (wait2LinkEvents.contains(personId)) {
            (Some(wait2LinkEvents.remove(personId).get.getAttributes.get("time")),"wait2Link",Some(departure.remove(personId).get.getAttributes.get("time")))
          } else {
            logger.warn(s"person neither in linkEnterEvents nor in wait2LinkEvents, time: $time, person: $personId")
            (None,"",None)
          }

          linkEnterTime.foreach{
            linkEnterTime => bw.write(s"$linkId,$linkEnterTime,$linkLeaveTime,$enterType,${departureTime.getOrElse(-1)}\n")
          }

        case _ =>
      }
    }
  }


}


