package beam.utils

import java.util.{Comparator, PriorityQueue}

import scala.annotation.tailrec
import scala.collection.mutable

case class ValueWithTime[T](value: T, time: Long)

private case class InternalValueWithTime[T](value: T, time: Long, var isAlive: Boolean)

class StuckFinderHelper[K] {

  private object Comparator extends Comparator[InternalValueWithTime[K]] {

    def compare(o1: InternalValueWithTime[K], o2: InternalValueWithTime[K]): Int = {
      // We could do `o1.time.compare(o2.time)`, but it will cause boxing
      java.lang.Long.compare(o1.time, o2.time)
    }
  }

  private[this] val pq = new PriorityQueue[InternalValueWithTime[K]](Comparator)
  private[this] val map = mutable.HashMap.empty[K, InternalValueWithTime[K]]

  def size: Int = map.size

  def add(time: Long, key: K): Unit = {
    map.get(key) match {
      case Some(_) =>
      // TODO Key is already there. What shall we do?!
      // 1 => Skip adding
      // 2 => Change map to be K -> List to store all different timestamps
      // Go with first approach for now
      case None =>
        val valueWithTs = InternalValueWithTime(key, time, isAlive = true)
        pq.add(valueWithTs)
        map.put(key, valueWithTs)
    }
  }

  def removeOldest(): Option[ValueWithTime[K]] = {
    val head = pollFirstAlive(pq)
    head.foreach { x =>
      removeByKey(x.value)
    }
    head
  }

  def removeByKey(key: K): Option[ValueWithTime[K]] = {
    val value = map.remove(key)
    // Mark removed element as not alive
    value.foreach { v =>
      v.isAlive = false
    }
    value.map { ts =>
      ValueWithTime(ts.value, ts.time)
    }
  }

  @tailrec
  private final def pollFirstAlive(
    pq: PriorityQueue[InternalValueWithTime[K]]
  ): Option[ValueWithTime[K]] = {
    if (pq.isEmpty) None
    else {
      val head = pq.poll()
      if (head.isAlive)
        Some(ValueWithTime(head.value, head.time))
      else
        pollFirstAlive(pq)
    }
  }
}
