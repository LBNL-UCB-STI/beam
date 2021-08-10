package beam.physsim.bprsim

/**
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
  def empty[A](implicit ord: Ordering[A]) = new JavaConcurrentPriorityQueue[A](ord)
}

class JavaConcurrentPriorityQueue[A](val ord: Ordering[A]) extends ConcurrentPriorityQueue[A] {
  private val backend = new java.util.concurrent.PriorityBlockingQueue[A](11, ord)

  override def +=(elem: A): JavaConcurrentPriorityQueue.this.type = {
    backend.put(elem)
    this
  }

  override def dequeue(): A = Option(backend.poll()).getOrElse(throw new NoSuchElementException)

  override def head: A = Option(backend.peek()).getOrElse(throw new NoSuchElementException)

  override def headOption: Option[A] = Option(backend.peek())

  override def isEmpty: Boolean = backend.isEmpty
}
