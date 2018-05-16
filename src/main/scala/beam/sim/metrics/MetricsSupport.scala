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

  private def measure[A](histogram: Histogram)(thunk: ⇒ A): A = {
    val start = System.nanoTime()
    try thunk finally {
      val latency = System.nanoTime() - start
      histogram.record(latency)
    }
  }
}

