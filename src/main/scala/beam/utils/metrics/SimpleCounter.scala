package beam.utils.metrics

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}

import akka.event.Logging.LogLevel
import akka.event.LoggingAdapter

import scala.collection.mutable

/**
  * Not thread safe. Intended to be used inside an actor.
  *
  * @author Dmitry Openkov
  */
class SimpleCounter(log: LoggingAdapter, logLevel: LogLevel, logMsg: String) {
  private val backend = new mutable.HashMap[Any, Long]
  private var countTimeStart: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)

  def count(key: Any): Long = {
    val prevValue = backend.getOrElse(key, 0L)
    val newValue = prevValue + 1
    backend.update(key, newValue)
    newValue
  }

  def tick(): Unit = {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val millis = ChronoUnit.MILLIS.between(countTimeStart, now)
    backend.keySet.foreach(key => processKey(key, millis))
    countTimeStart = now
  }

  private def processKey(key: Any, millis: Long): Unit = {
    val inquiryCounter = backend.getOrElse(key, 0L)
    val rate = if (millis > 0) inquiryCounter * 1000.0 / millis else -1
    if (rate > 0) {
      log.log(logLevel, logMsg, rate, key)
    }
    backend.update(key, 0L)
  }

}
