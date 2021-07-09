package beam.sim

import beam.sim.metrics.Metrics.isMetricsEnable
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import kamon.Kamon
import kamon.trace.Span

trait Tracing {

  def startKamonTracingIfEnabled(config: TypesafeConfig): Unit = {
    if (isMetricsEnable) {
      val kamonConfig = config.withFallback(ConfigFactory.defaultReference())
      Kamon.init(kamonConfig)
    }
  }

  final def tracing[B](operation: String, tag: (String, String))(body: => Span => B):B = {
    if (isMetricsEnable) {
      val executionSpan = Kamon.serverSpanBuilder(operation, "beam").start()
      executionSpan.tag(tag._1, tag._2)
      try {
        Kamon.runWithSpan(executionSpan) {
          body(executionSpan)
        }
      } finally {
        executionSpan.finish()
      }
    } else {
      body(Span.Empty)
    }
  }

}
