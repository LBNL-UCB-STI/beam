package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @author Dmitry Openkov
  */
class VolumeCalculator {
  implicit val reverseOrdering = Ordering[Double].reverse
  val linkToEvents = mutable.Map.empty[Id[Link], mutable.TreeMap[Double, Int]]

  def vehicleEntered(linkId: Id[Link], time: Double): Unit = {
    val events = linkToEvents.getOrElseUpdate(linkId, mutable.TreeMap.empty[Double, Int])
    val numEvents = events.getOrElseUpdate(time, 0)
    events.put(time, numEvents + 1)
  }

  def getVolume(linkId: Id[Link], time: Double): Int = {
    val events = linkToEvents.getOrElse(linkId, mutable.TreeMap.empty[Double, Int])
    val numberOfEvents = events.range(time, time - 30).values.sum
    numberOfEvents * (3600 / 30)
  }

  def dropEarlierThan(time: Double) = {
    linkToEvents.values.foreach { events =>
      //todo
    }
  }
}
