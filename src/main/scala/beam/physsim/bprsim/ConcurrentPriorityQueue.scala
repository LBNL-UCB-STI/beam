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

  override def +=(elem: A) = {
    backend.put(elem)
    this
  }

  override def dequeue() = Option(backend.poll()).getOrElse(throw new NoSuchElementException)

  override def head = Option(backend.peek()).getOrElse(throw new NoSuchElementException)

  override def headOption = Option(backend.peek())

  override def isEmpty = backend.isEmpty
}
