package beam.analysis

import beam.sim.metrics.Metrics.ShortLevel
import beam.sim.metrics.MetricsSupport
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable.ArrayBuffer

object AnalysisCollector extends MetricsSupport with LazyLogging {

  def rideHailRevenueAnalytics(data: ArrayBuffer[_]): Unit = {
    data.lastOption match {
      case Some(value) =>
        record("ride-hailing-revenue", ShortLevel, value.asInstanceOf[Double].toLong)
      case None =>
    }
  }
}
