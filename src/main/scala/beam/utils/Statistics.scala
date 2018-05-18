package beam.utils

import org.apache.commons.math3.stat.descriptive.rank.Percentile

case class Statistics
(
  numOfValues: Int,
  measureTimeMs: Long,
  minValue: Double,
  maxValue: Double,
  median: Double,
  p75: Double,
  p95: Double,
  sum: Double
) {
  override def toString: String = {
    val avg = sum / numOfValues
    s"numOfValues: $numOfValues, measureTimeMs: $measureTimeMs, [$minValue, $maxValue], median: $median, avg: $avg, p75: $p75, p95: $p95, sum: $sum"
  }
}

object Statistics {
  def apply(pq: Seq[Double]): Statistics = {
    if (pq.nonEmpty) {
      val start = System.currentTimeMillis()
      val min = pq.min
      val max = pq.max
      val percentile = new Percentile()
      percentile.setData(pq.toArray)
      val median = percentile.evaluate(50)
      val p75 = percentile.evaluate(75)
      val p95 = percentile.evaluate(95)
      val stop = System.currentTimeMillis()
      Statistics(numOfValues = pq.size, measureTimeMs = stop - start, minValue = min,
        maxValue = max, median = median, p75 = p75, p95 = p95, sum = pq.sum)
    }
    else {
      Statistics(numOfValues = 0, measureTimeMs = 0, minValue = Double.NaN,
        maxValue = Double.NaN, median = Double.NaN, p75 = Double.NaN, p95 = Double.NaN, sum = 0.0)
    }
  }
}
