package beam.utils

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class RepositioningDuration(repositioningDurationStart: Double, repositioningDurationEnd: Double)

object RepositioningValidation {

  val vehicleRepositioning = mutable.Map[String, ListBuffer[RepositioningDuration]]()

  def main(args: Array[String]): Unit = {
    var eventsFileFormat = "xml"
    if(args.length == 2){
      eventsFileFormat = args(1)
    }
    if(eventsFileFormat == "csv") {
      val (eventCount, foundEvent) = readCSVEvents(args(0))
      println(s"Total event count $eventCount")
      println(s"Event found $foundEvent")

    }
    else{
      val (eventCount, foundEvent) = readXMLEvents(args(0))
      println(s"Total event count $eventCount")
      println(s"Event found $foundEvent")
    }
    //readEvents("/home/rajnikant/IdeaProjects/beam/output/sf-light/sf-light-1k-xml__2019-10-02_03-28-09/ITERS/it.0/0.events.xml.gz")

  }

  private def readCSVEvents(path: String): (Int, Int) = {
    val (eventSeq, closeable) = EventReader.fromCsvFile(path, _ => true)
    var eventCount = 0
    var foundEvent = 0
    eventSeq.foreach(event => {
      if(event.getEventType == "PathTraversal"){
        eventCount += 1
        if(processForRepositioningDebug(event))
          foundEvent += 1
      }
    })
    (eventCount, foundEvent)
  }

  private def readXMLEvents(path: String): (Int, Int) = {
    val eventsManager = EventsUtils.createEventsManager()
    var eventCount = 0
    var foundEvent = 0
    eventsManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        if (event.getEventType == "PathTraversal") {
          eventCount += 1
          if(processForRepositioningDebug(PathTraversalEvent.apply(event)))
            foundEvent += 1
        }
      }
    })
    new MatsimEventsReader(eventsManager).readFile(path)
    (eventCount, foundEvent)
  }

  private def processForRepositioningDebug(event: PathTraversalEvent): Boolean = {
    var option = false
    if(event.numberOfPassengers > 0){
      vehicleRepositioning.get(event.vehicleId.toString).foreach(repositioningDurationBuffer => {
        if (repositioningDurationBuffer.size > 1) {
          val size = repositioningDurationBuffer.size
          val deadHeading = repositioningDurationBuffer.last
          val repositioning = repositioningDurationBuffer(size-2)
          if(deadHeading.repositioningDurationStart == repositioning.repositioningDurationEnd){
            println("Found PathTraversal event with start deadheading and end repositioning")
            option = true
          }
        }
        vehicleRepositioning.remove(event.vehicleId.toString)
      })
    }
    else {
      val listBuffer = vehicleRepositioning.getOrElse(event.vehicleId.toString, ListBuffer[RepositioningDuration]())
      listBuffer += RepositioningDuration(event.departureTime, event.arrivalTime)
      vehicleRepositioning.put(event.vehicleId.toString, listBuffer)
    }
    option
  }

  private def processForRepositioningDebug(event: Event): Boolean = {
    var option = false
    val attr = event.getAttributes
    val vehicleId = attr.get("vehicle")
    if(attr.get("numPassengers").toInt > 0){
      vehicleRepositioning.get(vehicleId).foreach(repositioningDurationBuffer => {
        if (repositioningDurationBuffer.size > 1) {
          val size = repositioningDurationBuffer.size
          val deadHeading = repositioningDurationBuffer.last
          val repositioning = repositioningDurationBuffer(size-2)
          if(deadHeading.repositioningDurationStart == repositioning.repositioningDurationEnd){
            println("Found PathTraversal event with start deadheading and end repositioning")
            option = true
          }
        }
        vehicleRepositioning.remove(vehicleId)
      })
    }
    else {
      val listBuffer = vehicleRepositioning.getOrElse(vehicleId, ListBuffer[RepositioningDuration]())
      listBuffer += RepositioningDuration(attr.get("departureTime").toInt, attr.get("arrivalTime").toInt)
      vehicleRepositioning.put(vehicleId, listBuffer)
    }
    option
  }
}
