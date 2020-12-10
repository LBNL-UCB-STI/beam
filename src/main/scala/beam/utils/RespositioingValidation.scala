package beam.utils

import beam.agentsim.events.PathTraversalEvent
import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class RepositioningDuration(repositioningDurationStart: Double, repositioningDurationEnd: Double)

object RepositioningValidation {

  val vehicleRepositioning: mutable.Map[String, ListBuffer[RepositioningDuration]] =
    mutable.Map[String, ListBuffer[RepositioningDuration]]()

  def main(args: Array[String]): Unit = {
    var eventsFileFormat = "xml"
    if (args.length == 2) {
      eventsFileFormat = args(1)
    }
    if (eventsFileFormat == "csv") {
      val (eventCount, foundEvent) = readCSVEvents(args(0))
      println(s"Total event count $eventCount")
      println(s"Event found $foundEvent")

    } else {
      val (eventCount, foundEvent) = readXMLEvents(args(0))
      println(s"Total event count $eventCount")
      println(s"Event found $foundEvent")
    }
    //readCSVEvents("/home/rajnikant/IdeaProjects/beam/output/sf-light/sf-light-1k-xml__2019-10-04_03-19-45/ITERS/it.0/0.events.csv.gz")

  }

  private def readCSVEvents(path: String): (Int, Int) = {
    val (eventSeq, _) = EventReader.fromCsvFile(path, _ => true)
    var eventCount = 0
    var foundEvent = 0
    eventSeq.foreach(event => {
      if (event.getEventType == "PathTraversal") {
        eventCount += 1
        if (processForRepositioningDebug(PathTraversalEvent.apply(event)))
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
          if (processForRepositioningDebug(PathTraversalEvent.apply(event)))
            foundEvent += 1
        }
      }
    })
    new MatsimEventsReader(eventsManager).readFile(path)
    (eventCount, foundEvent)
  }

  private def processForRepositioningDebug(event: PathTraversalEvent): Boolean = {
    var option = false
    val vehicleId = event.vehicleId.toString

    if (event.numberOfPassengers > 0) {
      if (event.mode == BeamMode.CAR && vehicleId.contains("rideHail")) {
        vehicleRepositioning
          .get(vehicleId)
          .foreach(repositioningDurationBuffer => {
            if (repositioningDurationBuffer.size > 1) {
              val size = repositioningDurationBuffer.size
              val deadHeading = repositioningDurationBuffer.last
              val repositioning = repositioningDurationBuffer(size - 2)
              if (deadHeading.repositioningDurationStart.equals(repositioning.repositioningDurationEnd)) {
                println("Found PathTraversal event with start deadheading and end repositioning")
                option = true
              }
            }
          })
      }
      vehicleRepositioning.remove(vehicleId)
    } else {
      val listBuffer = vehicleRepositioning.getOrElse(vehicleId, ListBuffer[RepositioningDuration]())
      listBuffer += RepositioningDuration(event.departureTime, event.arrivalTime)
      vehicleRepositioning.put(vehicleId, listBuffer)
    }
    option
  }
}
