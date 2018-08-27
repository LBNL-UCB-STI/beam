package beam.utils

import java.util.{Comparator, PriorityQueue}

import scala.collection.mutable

case class ValueWithTime[T](value: T, time: Long)

class StuckFinderHelper[K] {

  object Comparator extends Comparator[ValueWithTime[K]] {

    def compare(o1: ValueWithTime[K], o2: ValueWithTime[K]): Int = {
      o1.time.compare(o2.time)
    }
  }

  private[this] val pq = new PriorityQueue[ValueWithTime[K]](Comparator)
  private[this] val map = mutable.HashMap.empty[K, ValueWithTime[K]]

  def size: Int = {
    pq.size()
  }

  def add(time: Long, key: K): Unit = {
    map.get(key) match {
      case Some(x) =>
      // TODO Key is already there. What shall we do?!
      // 1 => Skip adding
      // 2 => Change map to be K -> List to store all different timestamps
      // Go with first approach for now
      case None =>
        val valueWithTs = ValueWithTime(key, time)
        pq.add(valueWithTs)
        map.put(key, valueWithTs)
    }
  }

  def removeOldest: Option[ValueWithTime[K]] = {
    if (pq.isEmpty) None
    else {
      val result = pq.poll()
      removeByKey(result.value)
      Some(result)
    }
  }

  def removeByKey(key: K): Option[ValueWithTime[K]] = {
    map.remove(key).map { ts =>
      pq.remove(ts)
      ts
    }
  }
}
