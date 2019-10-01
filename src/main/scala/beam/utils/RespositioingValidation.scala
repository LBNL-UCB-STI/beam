package beam.utils

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.vehicles.Vehicle

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class RepositioningDuration(repositioningDurationStart: Double, repositioningDurationEnd: Double)

object RepositioningValidation {

  val vehicleRepositioning = mutable.Map[Id[Vehicle], ListBuffer[RepositioningDuration]]()

  def main(args: Array[String]): Unit = {

    val (eventCount, foundEvent) = readEvents(args(0)) //readEvents("/home/rajnikant/IdeaProjects/beam/output/sf-light/sf-light-1k-xml__2019-10-02_03-28-09/ITERS/it.0/0.events.xml.gz")
    println(s"Total event count $eventCount")
    println(s"Event found $foundEvent")

  }

  private def readEvents(path: String): (Int, Int) = {
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
      vehicleRepositioning.get(event.vehicleId).foreach(repositioningDurationBuffer => {
        if (repositioningDurationBuffer.size > 1) {
          val size = repositioningDurationBuffer.size
          val deadHeading = repositioningDurationBuffer.last
          val repositioning = repositioningDurationBuffer(size-2)
          if(deadHeading.repositioningDurationStart == repositioning.repositioningDurationEnd){
            println("Found PathTraversal event with start deadheading and end repositioning")
            option = true
          }
        }
        vehicleRepositioning.remove(event.vehicleId)
      })
    }
    else {
      val listBuffer = vehicleRepositioning.getOrElse(event.vehicleId, ListBuffer[RepositioningDuration]())
      listBuffer += RepositioningDuration(event.departureTime, event.arrivalTime)
      vehicleRepositioning.put(event.vehicleId, listBuffer)
    }
    option
  }
}
