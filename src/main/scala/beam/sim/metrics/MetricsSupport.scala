package beam.sim.metrics

import beam.sim.metrics.Metrics.MetricLevel
import kamon.Kamon
import kamon.akka.AskPatternTimeoutWarningSettings.Off
import kamon.util.Latency

trait MetricsSupport {
  def metricLevel: MetricLevel = Metrics.VerboseLevel

  def countOccurrence(name: String, level: MetricLevel) = increment(name, level)

  def increment(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).increment()

  def decrement(name: String, level: MetricLevel) = if (isRightLevel(level)) Kamon.metrics.minMaxCounter(name).decrement()

  def latency[A](name: String, level: MetricLevel)(thunk: => A): A = if (isRightLevel(level)) Latency.measure(Kamon.metrics.histogram(name))(thunk) else thunk

  private def isRightLevel(level: MetricLevel) = level <= metricLevel && level != Off
}

