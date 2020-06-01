package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @author Dmitry Openkov
  */
class VolumeCalculator(val window: Int) {
  if (window <= 0) throw new IllegalArgumentException("Aggregation Time window must be greater than zero")
  private implicit val reverseOrdering = Ordering[Double].reverse
  private val linkToEvents = mutable.Map.empty[Id[Link], mutable.TreeMap[Double, Int]]

  def vehicleEntered(linkId: Id[Link], time: Double): Unit = {
    val events = linkToEvents.getOrElseUpdate(linkId, mutable.TreeMap.empty[Double, Int])
    val numEvents = events.getOrElseUpdate(time, 0)
    events.put(time, numEvents + 1)
    events --= events.keysIteratorFrom(time - window * 1.5)
  }

  def getVolume(linkId: Id[Link], time: Double): Double = {
    val events = linkToEvents.getOrElse(linkId, mutable.TreeMap.empty[Double, Int])
    val numberOfEvents = events.range(time, time - window).values.sum
    numberOfEvents * (3600.0 / window)
  }
}
