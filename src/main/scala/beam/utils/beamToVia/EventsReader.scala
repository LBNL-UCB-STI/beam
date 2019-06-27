package beam.utils.beamToVia

import beam.utils.EventReader
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object EventsReader {
  private def sortEvents(events: Traversable[Event]): Traversable[BeamEvent] = {
    val initQueue = mutable.PriorityQueue.empty[BeamEvent]((e2, e1) => e1.time.compare(e2.time))
    val sortedEvens = events.foldLeft(initQueue)((sorted, event) => {
      event.getEventType match {
        case BeamPathTraversal.EVENT_TYPE       => sorted += BeamPathTraversal(event)
        case BeamPersonLeavesVehicle.EVENT_TYPE => sorted += BeamPersonLeavesVehicle(event)
        case BeamPersonEntersVehicle.EVENT_TYPE => sorted += BeamPersonEntersVehicle(event)
        case _                                  =>
      }

      sorted
    })

    sortedEvens.toSeq
  }

  def fromFile(filePath: String): Option[Traversable[BeamEvent]] = {
    val extension = filePath.split('.').lastOption
    val unsorted = extension match {
      case Some("xml") => Some(EventReader.fromXmlFile(filePath).toArray)
      case Some("csv") => Some(fromCsv(filePath))
      case _           => None
    }

    unsorted match {
      case Some(events) => Some(sortEvents(events))
      case _            => None
    }
  }

  private def fromCsv(filePath: String): Array[Event] = {
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val eventsArray = events.toArray
    closable.close()

    eventsArray
  }
}
