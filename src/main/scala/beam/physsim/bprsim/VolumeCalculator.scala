package beam.physsim.bprsim

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link

import scala.annotation.tailrec
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
    val eventHoldler = linkToEvents(linkId)
    eventHoldler.numberOfEvents(time) * (3600 / timeWindow)
  }
}

class EventHolder(length: Int, countInterval: Int) {
  private val backend = Array.fill(length)(0)
  private var pointer = 0
  private var zeroIndexInterval = 0

  def addEvent(time: Double): Unit = {
    val interval = toInterval(time)
    val diff = interval - (zeroIndexInterval + pointer)
    if (diff >= length) {
      backend(pointer) = 1
      for (i <- 1 until length) backend(i) = 0
      pointer = 0
      zeroIndexInterval = interval
    } else if (diff >= 0) {
      fill(diff, 0)
      backend(pointer) = backend(pointer) + 1
    }
  }

  def numberOfEvents(time: Double) = {
    val interval = toInterval(time)
    val end = Math.min(interval, zeroIndexInterval + pointer)
    sum(interval - length + 1, end, 0)
  }

  @tailrec
  private def fill(n: Int, value: Int): Unit = {
    if (n > 0) {
      pointer += 1
      if (pointer >= length) {
        pointer = 0
        zeroIndexInterval = zeroIndexInterval + length
      }
      backend(pointer) = value
      fill(n - 1, value)
    }
  }

  @tailrec
  private def sum(start: Int, end: Int, acc: Int): Int = {
    if (start > end)
      acc
    else {
      val diff = start - zeroIndexInterval
      if (diff <= -length)
        sum(start + 1, end, acc)
      else if (diff < 0) {
        sum(start + 1, end, acc + backend(diff + length))
      } else if (diff >= length) {
        acc
      } else {
        sum(start + 1, end, acc + backend(diff))
      }
    }
  }

  private def toInterval(time: Double): Int = {
    (time / countInterval).toInt
  }
}

object EventHolder {

  def apply(timeWindow: Int): EventHolder = {
    val countInterval = Math.max(1, Math.min(60, timeWindow / 20))
    val actualWindow = timeWindow / countInterval
    new EventHolder(actualWindow, countInterval)
  }
}
