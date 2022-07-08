package beam.utils.beam_to_matsim.via_event

import scala.collection.mutable.ArrayBuffer

class ViaEventsCollection {

  val unsortedEvents: ArrayBuffer[ViaEvent] = ArrayBuffer.empty[ViaEvent]

  def getSortedEvents: Seq[ViaEvent] = {
    val arrayToSort = unsortedEvents.toArray
    val comparator = new java.util.Comparator[ViaEvent] {
      override def compare(ev1: ViaEvent, ev2: ViaEvent): Int = {
        ev1.time.compare(ev2.time)
      }
    }
    java.util.Arrays.parallelSort(arrayToSort, comparator)
    arrayToSort
  }

  def put(event: ViaEvent): Unit = unsortedEvents.append(event)

  def size: Int = unsortedEvents.length
}
