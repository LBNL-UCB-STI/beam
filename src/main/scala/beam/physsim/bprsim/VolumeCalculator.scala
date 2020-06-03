package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @author Dmitry Openkov
  */
class VolumeCalculator(val timeWindow: Int) {
  if (timeWindow <= 0) throw new IllegalArgumentException("Aggregation Time window must be greater than zero")
  private val linkToEvents = mutable.Map.empty[Id[Link], EventHolder]

  def vehicleEntered(linkId: Id[Link], time: Double): Unit = {
    val eventHolder = linkToEvents.getOrElseUpdate(linkId, EventHolder(timeWindow))
    eventHolder.addEvent(time)
  }

  def getVolume(linkId: Id[Link], time: Double): Double = {
    val eventHolder = linkToEvents(linkId)
    val (numEvents, tw) = eventHolder.numberOfEventsWithLength(time)
    numEvents * (3600 / tw)
  }
}

class EventHolder(length: Int, countInterval: Int, arrayOfZeros: Array[Int]) {
  private val backend = new Array[Int](length)
  private var index = 0
  private var zeroIndexInterval = 0
  private def currentInterval = zeroIndexInterval + index

  def addEvent(time: Double): Unit = {
    val interval = toInterval(time)
    val diff = interval - currentInterval
    if (diff >= length) {
      //event to far from current point
      initWith(interval)
      backend(index) = 1
    } else if (diff >= 0) {
      if (diff > 0) {
        circularCopyZeros(currentInterval + 1, interval)
      }
      index = getCircularIndex(interval)
      backend(index) = backend(index) + 1
      zeroIndexInterval = getZeroInterval(interval)
    } else if (diff <= -length) {
      //event to far in the past, do nothing about it
    } else {
      val ind = getCircularIndex(interval)
      backend(ind) = backend(ind) + 1
    }
  }

  def numberOfEventsWithLength(time: Double) = {
    val interval = toInterval(time)
    val diff = interval - currentInterval
    if (diff > 0 || diff <= -length) {
      //cannot handle future events or events in the far past
      (0, 1)
    } else if (diff == 0) {
      (backend.sum, length * countInterval)
    } else {
      val start = getCircularIndex(currentInterval + 1)
      val till = getCircularIndex(interval)
      var sum = 0
      if (start <= till) {
        for (i <- start to till) sum += backend(i)
      } else {
        for (i <- start until length) sum += backend(i)
        for (i <- 0 to till) sum += backend(i)
      }
      val len = interval - (currentInterval - length)
      (sum, len * countInterval)
    }
  }

  private def initWith(zeroInterval: Int): Unit = {
    Array.copy(arrayOfZeros, 0, backend, 0, length)
    index = 0
    zeroIndexInterval = zeroInterval
  }

  private def circularCopyZeros(start: Int, end: Int): Unit = {
    val st = getCircularIndex(start)
    val til = getCircularIndex(end)
    if (st <= til) {
      Array.copy(arrayOfZeros, 0, backend, st, til - st + 1)
    } else {
      Array.copy(arrayOfZeros, 0, backend, st, length - st)
      Array.copy(arrayOfZeros, 0, backend, 0, til + 1)
    }
  }

  private def getCircularIndex(interval: Int): Int = ((interval - zeroIndexInterval) % length + length) % length

  private def getZeroInterval(interval: Int): Int = zeroIndexInterval + (interval - zeroIndexInterval) / length * length

  private def toInterval(time: Double): Int = {
    (time / countInterval).toInt
  }
}

object EventHolder {
  var arrayOfZeros: Array[Int] = _

  def apply(timeWindow: Int): EventHolder = {
    val countInterval = Math.max(1, Math.min(60, timeWindow / 20))
    val actualWindow = timeWindow / countInterval
    if (arrayOfZeros == null || arrayOfZeros.length != actualWindow) {
      arrayOfZeros = new Array[Int](actualWindow)
    }
    new EventHolder(actualWindow, countInterval, arrayOfZeros)
  }
}
