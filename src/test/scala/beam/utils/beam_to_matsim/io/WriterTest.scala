package beam.utils.beam_to_matsim.io

import beam.utils.beam_to_matsim.via_event.{ViaEvent, ViaEventsCollection}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.xml.Elem

class WriterTest extends AnyFunSuite with Matchers {

  case class VEvent(var time: Double) extends ViaEvent with Ordered[VEvent] {

    override val link: Int = 0

    override def toXml: Elem = ???

    override def toXmlString: String = ???

    override def compare(that: VEvent): Int = time.compare(that.time)
  }

  def resultAndDuration[T](func: => T): (T, Double) = {
    val t1 = System.nanoTime
    val result = func
    val duration = (System.nanoTime - t1) / 1e9d
    (result, duration)
  }

  def sortingPriorityQueue(unsortedSeq: Seq[ViaEvent]): Seq[ViaEvent] = {
    val queue: mutable.PriorityQueue[ViaEvent] = mutable.PriorityQueue()((e1, e2) => e2.time.compare(e1.time))
    unsortedSeq.foreach { line => queue.enqueue(line) }

    val sortedSeq = mutable.ArrayBuffer.empty[ViaEvent]
    while (queue.nonEmpty) {
      sortedSeq += queue.dequeue()
    }
    sortedSeq
  }

  def sortingJavaArrayParallel(unsortedSeq: Seq[ViaEvent]): Seq[ViaEvent] = {
    val eventsCollection = new ViaEventsCollection()
    unsortedSeq.foreach(eventsCollection.put)
    eventsCollection.getSortedEvents
  }

  test("testWriteViaEventsQueue") {
    val experimentLen = 2 * 1000000
    def getUnsortedSeq: Seq[ViaEvent] = (0 until experimentLen).map { _ => VEvent(math.random()) }
    val unsorted1 = getUnsortedSeq
    val unsorted2 = getUnsortedSeq

    val (sortedSeq1, duration1) = resultAndDuration { sortingPriorityQueue(unsorted1) }
    val (sortedSeq2, duration2) = resultAndDuration { sortingJavaArrayParallel(unsorted2) }

    sortedSeq1.size shouldBe experimentLen
    sortedSeq2.size shouldBe experimentLen
    sortedSeq1.map(_.time) shouldBe sorted
    sortedSeq2.map(_.time) shouldBe sorted
    duration2 * 4 should be < duration1
  }

}
