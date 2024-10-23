package beam.utils.metrics

import scala.collection.mutable

class TemporalEventCounter[ID](val timeBinLengthInSeconds: Int) {
  private case class TimeBinWithId(binNum: Int, id: ID)

  private val backend = mutable.HashMap.empty[TimeBinWithId, Int]

  def addTemporalEvent(eventId: ID, startTime: Double, endTime: Double): Unit = {
    val startBin = Math.round(startTime / timeBinLengthInSeconds).toInt
    val endBin = Math.round(endTime / timeBinLengthInSeconds).toInt
    for (binNum <- startBin until endBin) {
      val key = TimeBinWithId(binNum, eventId)
      val currentCount = backend.getOrElse(key, 0)
      backend.put(key, currentCount + 1)
    }
  }

  def getEventCount(id: ID, time: Double): Int = {
    val bin = Math.round(time / timeBinLengthInSeconds).toInt
    backend.getOrElse(TimeBinWithId(bin, id), 0)
  }

}
