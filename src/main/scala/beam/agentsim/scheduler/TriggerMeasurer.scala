package beam.agentsim.scheduler

import java.util.concurrent.TimeUnit

import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.utils.Statistics
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/*
  This is dumb implementation. We store all the data to build percentiles. There are different approaches to get percentile on stream
  More: https://blog.superfeedr.com/streaming-percentiles/
 */
class TriggerMeasurer extends LazyLogging {
  private val triggerWithIdToStartTime: mutable.Map[TriggerWithId, Long] =
    mutable.Map[TriggerWithId, Long]()
  private val triggerTypeToOccurrence: mutable.Map[Class[_], ArrayBuffer[Long]] =
    mutable.Map[Class[_], ArrayBuffer[Long]]()

  def sent(t: TriggerWithId): Unit = {
    triggerWithIdToStartTime.put(t, System.nanoTime())
  }

  def resolved(t: TriggerWithId): Unit = {
    triggerWithIdToStartTime.get(t) match {
      case Some(startTime) =>
        val stopTime = System.nanoTime()
        val diff = TimeUnit.NANOSECONDS.toMillis(stopTime - startTime)
        val triggerClass = t.trigger.getClass
        triggerTypeToOccurrence.get(triggerClass) match {
          case Some(buffer) =>
            buffer.append(diff)
          case None =>
            val buffer = ArrayBuffer[Long](diff)
            triggerTypeToOccurrence.put(triggerClass, buffer)
        }

      case None =>
        logger.error(s"Can't find $t in triggerWithIdToStartTime")
    }
  }

  def getStat: String = {
    val sb = new mutable.StringBuilder()
    val nl = System.lineSeparator()
    triggerTypeToOccurrence.foreach {
      case (clazz, buf) =>
        val s = Statistics(buf.map(_.toDouble))
        sb.append(s"${nl}Type: $clazz${nl}Stats: $s$nl".stripMargin)
    }
    sb.toString()
  }
}
