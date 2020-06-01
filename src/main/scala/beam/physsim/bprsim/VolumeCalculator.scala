package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @author Dmitry Openkov
  */
class VolumeCalculator(val timeWindow: Int, val timeToKeepEvents: Int) {
  if (timeWindow <= 0) throw new IllegalArgumentException("Aggregation Time window must be greater than zero")
  private val countInterval = Math.max(1, Math.min(60, timeWindow / 20))
  private val keepEventsInterval = Math.max(timeWindow, timeToKeepEvents)
  private implicit val reverseOrdering = Ordering[Int].reverse
  private val linkToEvents = mutable.Map.empty[Id[Link], mutable.TreeMap[Int, Int]]

  def vehicleEntered(linkId: Id[Link], time: Double): Unit = {
    val events = linkToEvents.getOrElseUpdate(linkId, mutable.TreeMap.empty[Int, Int])
    val interval = toInterval(time)
    val numEvents = events.getOrElseUpdate(interval, 0)
    events.put(interval, numEvents + 1)
    events --= events.keysIteratorFrom(interval - keepEventsInterval)
  }

  def getVolume(linkId: Id[Link], time: Double): Double = {
    val events = linkToEvents.getOrElse(linkId, mutable.TreeMap.empty[Int, Int])
    val interval = toInterval(time)
    val numberOfEvents = events.range(interval, interval - timeWindow).values.sum
    numberOfEvents * (3600.0 / timeWindow)
  }

  private def toInterval(time: Double): Int = {
    (time / countInterval).toInt * countInterval
  }
}
