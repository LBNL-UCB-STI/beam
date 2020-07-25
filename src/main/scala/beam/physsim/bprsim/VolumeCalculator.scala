package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.collection.mutable

/**
  * Not thread-safe
  * @param timeWindow the time interval in seconds during which the number of events is counted
  * @author Dmitry Openkov
  */
class VolumeCalculator(val timeWindow: Int) {
  if (timeWindow <= 0) throw new IllegalArgumentException("Aggregation Time window must be greater than zero")
  private val linkToEvents = mutable.Map.empty[Id[Link], EventHolder]

  def vehicleEntered(linkId: Id[Link], time: Double, isCACC: Boolean): Unit = {
    val eventHolder = linkToEvents.getOrElseUpdate(linkId, EventHolder(timeWindow))
    eventHolder.addEvent(time, isCACC)
  }

  /**
    *
    * @param linkId the link id
    * @param time the simulation time
    * @return inFlow volume (current number of in flow vehicles per hour) and CACC share
    */
  def getVolumeAndCACCShare(linkId: Id[Link], time: Double): (Double, Double) = {
    val eventHolder = linkToEvents(linkId)
    val (numEvents, tw) = eventHolder.numberOfEventsWithLength(time)
    val cacc = (numEvents >> 32).toInt
    val regular = (numEvents & 0xFFFFFFFFL).toInt
    val total = cacc + regular
    val caccShare = cacc.toDouble / total
    (total * (3600 / tw), caccShare)
  }
}

/**
  * It holds number of events for a particular time interval
  * @param length number of time intervals that holds number of entered vehicles
  * @param countIntervalLength the length of time interval in seconds
  * @param arrayOfZeros just shared array of zeros of length equals length for quick backend reset
  */
class EventHolder(length: Int, countIntervalLength: Int, arrayOfZeros: Array[Long]) {

  /**
    * This contains Int's: the high integer is the number of CACC enabled vehicles, the low integer is the number of regular
    * vehicles at that time interval which is corresponds to the index of the element.
    * This complicated structure is done for performance reasons.
    * We could replace this array of Int's with a Map: time interval -> Pair of numbers of vehicles,
    * but this gives additional GC pauses.
    */
  private val backend = new Array[Long](length)

  /**
    * index of interval where the newest event is counted
    */
  private var index = 0

  /**
    * Interval number that is at backend(0)
    */
  private var zeroIndexInterval = 0
  private def currentInterval = zeroIndexInterval + index

  def addEvent(time: Double, isCACC: Boolean): Unit = {
    val interval = toInterval(time)
    val diff = interval - currentInterval
    if (diff >= length) {
      //event to far from the current point
      initWith(interval)
      backend(index) = increment(0, isCACC)
    } else if (diff >= 0) {
      if (diff > 0) {
        circularCopyZeros(currentInterval + 1, interval)
      }
      index = getCircularIndex(interval)
      backend(index) = increment(backend(index), isCACC)
      zeroIndexInterval = getZeroInterval(interval)
    } else if (diff <= -length) {
      //event to far in the past, do nothing about it
    } else {
      val ind = getCircularIndex(interval)
      backend(ind) = increment(backend(ind), isCACC)
    }
  }

  /**
    * @param time the sim time
    * @return number of events (low integer is number of regular vehicles, high integer is number of CACC vehicles)
    *         and time interval in seconds during which this happens
    */
  def numberOfEventsWithLength(time: Double): (Long, Int) = {
    val interval = toInterval(time)
    val diff = interval - currentInterval
    if (diff > 0 || diff <= -length) {
      //cannot handle future events or events in the far past
      (0, 1)
    } else if (diff == 0) {
      (backend.sum, length * countIntervalLength)
    } else {
      val start = getCircularIndex(currentInterval + 1)
      val till = getCircularIndex(interval)
      var sum = 0L
      if (start <= till) {
        for (i <- start to till) sum += backend(i)
      } else {
        for (i <- start until length) sum += backend(i)
        for (i <- 0 to till) sum += backend(i)
      }
      val len = interval - (currentInterval - length)
      (sum, len * countIntervalLength)
    }
  }

  def increment(value: Long, isCACC: Boolean): Long = {
    if (isCACC) {
      value + 0x100000000L
    } else {
      value + 1
    }
  }

  /**
    * Empties the backend and set the new zero interval
    * @param zeroInterval the new zero interval
    */
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
    (time / countIntervalLength).toInt
  }
}

object EventHolder {
  var arrayOfZeros: Array[Long] = _

  def apply(timeWindow: Int): EventHolder = {
    val countInterval = Math.max(1, Math.min(60, timeWindow / 20))
    val actualWindow = timeWindow / countInterval
    if (arrayOfZeros == null || arrayOfZeros.length != actualWindow) {
      arrayOfZeros = new Array[Long](actualWindow)
    }
    new EventHolder(actualWindow, countInterval, arrayOfZeros)
  }
}
