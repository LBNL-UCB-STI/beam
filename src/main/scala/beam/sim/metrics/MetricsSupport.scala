package beam.sim.metrics

import beam.sim.metrics.Metrics._
import kamon.Kamon
import kamon.metric.Histogram

trait MetricsSupport {

  def countOccurrence(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.counter(name).increment()

  def increment(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.rangeSampler(name).increment()

  def decrement(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.rangeSampler(name).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A =  {
    if (isRightLevel(level)) {
      measure(Kamon.histogram(name))(thunk)
    }
    else
      thunk
  }

  def latency(name: String, level: MetricLevel, nanoTime: Long): Unit = {
    //if (isRightLevel(level)) Kamon.metrics.histogram(name).record(nanoTime)
  }

  def latencyIfNonNull[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) {
    val resultWithTime = measure(thunk)
    if(resultWithTime._1 != null) {
      latency(name, level, resultWithTime._2)
    }
    resultWithTime._1
  } else thunk

  def measure[A](thunk: ⇒ A): (A, Long) = {
    val start = System.nanoTime()
    (thunk, System.nanoTime() - start)
  }

  def measure[A](histogram: Histogram)(thunk: ⇒ A): A = {
    val start = System.nanoTime()
    try thunk finally {
      val latency = System.nanoTime() - start
      histogram.record(latency)
    }
  }
}

