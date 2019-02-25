package beam.sim.metrics

import beam.sim.metrics.Metrics._
import kamon.Kamon
import kamon.metric.instrument.Histogram
import kamon.util.Latency

import scala.collection.JavaConverters._

trait MetricsSupport {

//  def countOccurrence(name: String, level: MetricLevel, tags: Map[String, String] = Map.empty): Unit = if (isRightLevel(level)) Kamon.metrics.counter(name, defaultTags ++ tags).increment()

  def countOccurrence(
    name: String,
    times: Long = 1,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit =
    if (isRightLevel(level)) Kamon.metrics.counter(name, defaultTags ++ tags).increment(times)

  def countOccurrenceJava(
    name: String,
    times: Long = 1,
    level: MetricLevel,
    tags: java.util.Map[String, String] = new java.util.HashMap()
  ): Unit = countOccurrence(name, times, level, tags.asScala.toMap)

  def increment(name: String, level: MetricLevel): Unit =
    if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name, defaultTags).increment()

  def decrement(name: String, level: MetricLevel): Unit =
    if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name, defaultTags).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A =
    if (isRightLevel(level)) Latency.measure(Kamon.metrics.histogram(name, defaultTags))(thunk)
    else thunk

  def record(
    name: String,
    level: MetricLevel,
    nanoTime: Long,
    tags: Map[String, String] = Map.empty
  ): Unit =
    if (isRightLevel(level)) Kamon.metrics.histogram(name, defaultTags ++ tags).record(nanoTime)

  def latencyIfNonNull[A](name: String, level: MetricLevel)(thunk: => A): A =
    if (isRightLevel(level)) {
      val resultWithTime = measure(thunk)
      if (resultWithTime._1 != null) {
        record(name, level, resultWithTime._2)
      }
      resultWithTime._1
    } else thunk

  def startMeasuringIteration(itNum: Int): Unit = startMeasuring("iteration", ShortLevel)

  def stopMeasuringIteration(): Unit = stopMeasuring()

  def startMeasuring(name: String, level: MetricLevel): Unit =
    startMeasuring(name, level, Map.empty)

  def startMeasuring(name: String, level: MetricLevel, tags: Map[String, String]): Unit =
    if (isRightLevel(level))
      Metrics.setCurrentContext(Kamon.tracer.newContext(name, None, defaultTags ++ tags))

  def stopMeasuring(): Unit =
    if (Metrics.currentContext != null && !Metrics.currentContext.isClosed)
      Metrics.currentContext.finish()

  def startSegment(name: String, categry: String): Unit =
    if (Metrics.currentContext != null && !Metrics.currentContext.isClosed && !currentSegments
          .contains(name + ":" + categry))
      currentSegments += (name + ":" + categry -> Metrics.currentContext.startSegment(
        name,
        categry,
        "kamon",
        defaultTags
      ))

  def endSegment(name: String, categry: String): Unit =
    currentSegments.remove(name + ":" + categry) match {
      case Some(segment) =>
        segment.finish()
      case _ =>
    }

  def measure[A](thunk: => A): (A, Long) = {
    val start = System.nanoTime()
    (thunk, System.nanoTime() - start)
  }

  def measure[A](histogram: Histogram)(thunk: => A): A = {
    val start = System.nanoTime()
    try thunk
    finally {
      val latency = System.nanoTime() - start
      histogram.record(latency)
    }
  }
}
