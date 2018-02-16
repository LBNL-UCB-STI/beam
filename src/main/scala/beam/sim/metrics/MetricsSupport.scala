package beam.sim.metrics

import beam.sim.metrics.Metrics.MetricLevel
import kamon.Kamon
import kamon.akka.AskPatternTimeoutWarningSettings.Off
import kamon.metric.instrument.Histogram
import kamon.util.Latency

trait MetricsSupport {
  def metricLevel: MetricLevel = Metrics.VerboseLevel

  def countOccurrence(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.counter(name).increment()

  def increment(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).increment()

  def decrement(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) Latency.measure(Kamon.metrics.histogram(name))(thunk) else thunk

  private def isRightLevel(level: MetricLevel) = level <= metricLevel && level != Off

  private def measure[A](histogram: Histogram)(thunk: ⇒ A): A = {
    val start = System.nanoTime()
    try thunk finally {
      val latency = System.nanoTime() - start
      histogram.record(latency)
    }
  }
}

