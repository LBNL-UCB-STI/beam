package beam.physsim.bprsim

/**
  * This priority queue supports FIFO for equal priority entities
  * @author Dmitry Openkov
  */
trait ConcurrentPriorityQueue[A] {

  def +=(elem: A): this.type

  def dequeue(): A

  def head: A

  def headOption: Option[A]

  def isEmpty: Boolean

  def nonEmpty: Boolean = !nonEmpty

}

object ConcurrentPriorityQueue {
  def empty[A](implicit ord: Ordering[A]) = new JavaConcurrentPriorityQueue[A]()
}

import java.util.concurrent.atomic.AtomicLong

class JavaConcurrentPriorityQueue[A](implicit val ord: Ordering[A]) extends ConcurrentPriorityQueue[A] {
  private val backend = new java.util.concurrent.PriorityBlockingQueue[FIFOEntry[A]]()
  private val seq = new AtomicLong(0)

  override def +=(elem: A) = {
    backend.put(new FIFOEntry(elem))
    this
  }

  override def dequeue() = Option(backend.poll()).map(_.entry).getOrElse(throw new NoSuchElementException)

  override def head = Option(backend.peek()).map(_.entry).getOrElse(throw new NoSuchElementException)

  override def headOption = Option(backend.peek()).map(_.entry)

  override def isEmpty = backend.isEmpty

  class FIFOEntry[E](val entry: E)(implicit val ord: Ordering[E]) extends Comparable[FIFOEntry[E]] {
    val seqNum = seq.getAndIncrement

    override def compareTo(other: FIFOEntry[E]) = {
      val res = ord.compare(this.entry, other.entry)
      if (res == 0 && (other.entry != this.entry))
        if (this.seqNum < other.seqNum) -1 else 1
      else res
    }
  }
}
