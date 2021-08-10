package beam.utils

/**
  * Created by sfeygin on 2/6/17.
  */

object Stats {
  final val STATS_EPS = 1e-12
  final val INV_SQRT_2PI = 1.0 / Math.sqrt(2.0 * Math.PI)

}

class Stats[T](values: Vector[T])(implicit ev$1: T => Double) {
  class _Stats(var minValue: Double, var maxValue: Double, var sum: Double, var sumSqr: Double)
  require(values.nonEmpty, "Stats: Cannot initialize stats with undefined values")
  private[this] val sums = values.foldLeft((0.0, 0.0))((acc, s) => (acc._1 + s, acc._2 + s * s))

  @inline
  lazy val mean: Double = sums._1 / values.size
  lazy val variance: Double = (sums._2 - mean * mean * values.size) / (values.size - 1)
  lazy val stdDev: Double = Math.sqrt(variance)

}
