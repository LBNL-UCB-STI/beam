package beam.physsim.bprsim

/**
  * @author Dmitry Openkov
  */
trait ConcurrentSortedCollection[A] {

  def +=(elem: A): this.type

  def ++=(xs: TraversableOnce[A]): this.type

  def head: A

  def headOption: Option[A] = if (isEmpty) None else Some(head)

  def isEmpty: Boolean

  def nonEmpty: Boolean = !nonEmpty

  def clear(): Unit

  def foreach[U](f: A => U): Unit

}

object ConcurrentSortedCollection {
  def empty[A](implicit ord: Ordering[A]) = new JavaConcurrentSortedCollection[A]()
}

import java.util.concurrent.atomic.AtomicLong

class JavaConcurrentSortedCollection[A](implicit val ord: Ordering[A]) extends ConcurrentSortedCollection[A] {
  private val backend = new java.util.concurrent.ConcurrentSkipListSet[FIFOEntry[A]]()
  private val seq = new AtomicLong(0)

  override def +=(elem: A) = {
    backend.add(new FIFOEntry(elem))
    this
  }

  override def ++=(xs: TraversableOnce[A]) = {
    xs.foreach(elem => this += elem)
    this
  }

  override def head = backend.first().entry

  override def isEmpty = backend.isEmpty

  private class FIFOEntry[E](val entry: E)(implicit val ord: Ordering[E]) extends Comparable[FIFOEntry[E]] {
    val seqNum = seq.getAndIncrement

    override def compareTo(other: FIFOEntry[E]) = {
      val res = ord.compare(this.entry, other.entry)
      if (res == 0 && (other.entry != this.entry))
        if (this.seqNum < other.seqNum) -1 else 1
      else res
    }
  }

  override def clear(): Unit = backend.clear()

  override def foreach[U](f: A => U): Unit = {
    backend.forEach(t => f(t.entry))
  }
}
