package beam.utils.beamToVia

import beam.utils.EventReader
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamEventReader}
import beam.utils.beamToVia.beamEventsFilter.MutableSamplingFilter
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object BeamEventsReader {

  def fromFileFoldLeft[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): Option[T] = {
    Console.println("started reading a file " + filePath + " for folding")

    val extension = filePath.split('.').lastOption
    val result = extension match {
      case Some("xml") =>
        Some(
          EventReader
            .fromXmlFile(filePath)
            .foldLeft(accumulator)((acc, event) => {
              BeamEventReader.read(event) match {
                case Some(beamEvent) => foldLeft(acc, beamEvent)
                case _               => acc
              }
            })
        )
      case Some("csv") => Some(fromCsvFold(filePath, accumulator, foldLeft))
      case _           => None
    }

    Console.println("done")
    result
  }
/*
  def fromFileWithFilter(filePath: String, mutableFilter: MutableSamplingFilter): Option[Traversable[BeamEvent]] = {
    Console.println("started reading a file " + filePath)

    val extension = filePath.split('.').lastOption
    val events = extension match {
      case Some("xml") => Some(sortAndTransform(EventReader.fromXmlFile(filePath), mutableFilter))
      case Some("csv") => Some(fromCsv(filePath, mutableFilter))
      case _           => None
    }

    events match {
      case Some(coll) => Console.println(coll.size + " events read")
      case _          => Console.println("read nothing ...")
    }

    events
  }

  private def sortAndTransform(
    unsorted: TraversableOnce[Event],
    mutableEventsFilter: MutableSamplingFilter
  ): IndexedSeq[BeamEvent] = {
    val emptyAcc = mutable.PriorityQueue.empty[BeamEvent]((e1, e2) => e2.time.compare(e1.time))
    val (queuedEvents, _) = unsorted.foldLeft((emptyAcc, mutableEventsFilter)) {
      case ((acc, eventsFilter), event) =>
        BeamEventReader.read(event) match {
          case Some(beamEvent) => eventsFilter.filter(beamEvent).foreach(acc.enqueue(_))
          case _               =>
        }

        (acc, eventsFilter)
    }

    val sortedEvents = queuedEvents.dequeueAll
    sortedEvents
  }

  private def fromCsv(filePath: String, mutableEventsFilter: MutableSamplingFilter): IndexedSeq[BeamEvent] = {
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val sortedAndFilteredEvents = sortAndTransform(events, mutableEventsFilter)
    closable.close()

    sortedAndFilteredEvents
  }*/

  private def fromCsvFold[T](filePath: String, accumulator: T, foldLeft: (T, BeamEvent) => T): T = {
    val (events, closable) = EventReader.fromCsvFile(filePath, _ => true)
    val result = events.foldLeft(accumulator)((acc, event) => {
      BeamEventReader.read(event) match {
        case Some(beamEvent) => foldLeft(acc, beamEvent)
        case _               => acc
      }
    })

    closable.close()
    result
  }
}
