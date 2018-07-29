package beam.utils

import java.util.{Comparator, PriorityQueue}

import scala.collection.mutable

case class ValueWithTs[T](value: T, ts: Long)

class StuckFinderHelper[K] {

  object Comparator extends Comparator[ValueWithTs[K]] {
    def compare(o1: ValueWithTs[K], o2: ValueWithTs[K]): Int = {
      o1.ts.compare(o2.ts)
    }
  }

  private[this] val pq = new PriorityQueue[ValueWithTs[K]](Comparator)
  private[this] val map = mutable.HashMap.empty[K, ValueWithTs[K]]

  def size: Int = {
    pq.size()
  }

  def add(ts: Long, key: K): Unit = {
    map.get(key) match {
      case Some(x) =>
      // TODO Key is already there. What shall we do?!
      // 1 => Skip adding
      // 2 => Change map to be K -> List to store all different timestamps
      // Go with first approach for now
      case None =>
        val valueWithTs = ValueWithTs(key, ts)
        pq.add(valueWithTs)
        map.put(key, valueWithTs)
    }
  }

  def removeOldest: Option[ValueWithTs[K]] = {
    if (pq.isEmpty) None
    else {
      val result = pq.poll()
      removeByKey(result.value)
      Some(result)
    }
  }

  def removeByKey(key: K): Option[ValueWithTs[K]] = {
    map.remove(key).map { ts =>
      pq.remove(ts)
      ts
    }
  }
}

