package beam.utils.watcher

import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.duration._

object MethodWatcher {

  def withLoggingInvocationTime[T](
    name: String,
    m: => T,
    logger: Logger = LoggerFactory.getLogger(this.getClass),
    timeUnit: TimeUnit = SECONDS
  ): T = {
    val startTime = System.nanoTime()
    val res = m
    val stopTime = System.nanoTime()
    val duration = Duration(stopTime - startTime, NANOSECONDS)

    logger.info(
      "Invocation of '{}' took {} {}",
      name: AnyRef,
      duration.toUnit(timeUnit).toString: AnyRef,
      timeUnit: AnyRef
    )

    res
  }

}
