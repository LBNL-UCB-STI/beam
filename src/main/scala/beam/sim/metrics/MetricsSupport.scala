package beam.sim.metrics

import beam.sim.metrics.Metrics._
import kamon.Kamon
import kamon.tag.TagSet

import scala.collection.JavaConverters._

trait MetricsSupport {

  def countOccurrence(
    name: String,
    times: Long = 1,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit = {
    if (isRightLevel(level)) {
      Kamon.counter(name).withTags(TagSet.from(defaultTags ++ tags)).increment(times)
    }
  }

  def countOccurrenceJava(
    name: String,
    times: Long = 1,
    level: MetricLevel,
    tags: java.util.Map[String, String] = new java.util.HashMap()
  ): Unit = countOccurrence(name, times, level, tags.asScala.toMap)

  def increment(name: String, level: MetricLevel): Unit = {
    if (isRightLevel(level)) {
      Kamon.counter(name).withTags(TagSet.from(defaultTags)).increment()
    }
  }

  def decrement(name: String, level: MetricLevel): Unit = {
    if (isRightLevel(level)) {
      Kamon.gauge(name).withTags(TagSet.from(defaultTags)).decrement()
    }
  }

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A = {
    if (isRightLevel(level)) {
      val start = System.currentTimeMillis()
      val res = thunk
      Kamon.histogram(name).withTags(TagSet.from(defaultTags)).record(System.currentTimeMillis() - start)
      res
    } else thunk
  }

  def record(
    name: String,
    level: MetricLevel,
    msTime: Long,
    tags: Map[String, String] = Map.empty
  ): Unit = {
    if (isRightLevel(level)) {
      Kamon.histogram(name).withTags(TagSet.from(defaultTags ++ tags)).record(msTime)
    }
  }

  def startMeasuringIteration(): Unit = startMeasuring("iteration", ShortLevel)

  def stopMeasuringIteration(): Unit = stopMeasuring("iteration")

  def startMeasuring(name: String, level: MetricLevel = ShortLevel): Unit =
    startMeasuring(name, level, Map.empty)

  def startMeasuring(name: String, level: MetricLevel, tags: Map[String, String]): Unit = {
    if (isRightLevel(level)) {
      val startedTimer = Kamon.timer(name).withTags(TagSet.from(tags)).start()
      Metrics.addTimer(name, startedTimer)
    }
  }

  def stopMeasuring(name: String): Unit = {
    Metrics.getTimer(name).foreach(_.stop())
  }
}
