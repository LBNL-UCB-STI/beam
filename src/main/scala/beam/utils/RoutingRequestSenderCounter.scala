package beam.utils

import java.time.temporal.ChronoUnit
import java.time.{ZoneOffset, ZonedDateTime}
import java.util.concurrent.atomic.AtomicLong

import kamon.Kamon

object RoutingRequestSenderCounter {
  private val n: AtomicLong = new AtomicLong(0L)
  private var startTime: Option[ZonedDateTime] = None
  private val obj: Object = new Object

  def sent(): Unit = {
    Kamon.counter("sending-routing-requests")
    obj.synchronized {
      if (startTime.isEmpty)
        startTime = Some(ZonedDateTime.now(ZoneOffset.UTC))
    }
    n.incrementAndGet()
  }
  def rate: Double = {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val seconds = ChronoUnit.SECONDS.between(startTime.getOrElse(now), now)
    if (seconds > 0) {
      n.get().toDouble / seconds
    }else {
      0.0
    }
  }
}