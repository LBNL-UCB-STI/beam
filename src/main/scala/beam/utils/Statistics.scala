package beam.utils

import org.apache.commons.math3.stat.descriptive.rank.Percentile

case class Statistics(
  numOfValues: Int,
  measureTimeMs: Long,
  minValue: Double,
  maxValue: Double,
  median: Double,
  avg: Double,
  p75: Double,
  p95: Double,
  p99: Double,
  `p99.95`: Double,
  `p99.99`: Double,
  sum: Double
) {
  override def toString: String = {
    f"numOfValues: $numOfValues, measureTimeMs: $measureTimeMs, [$minValue%.2f, $maxValue%.2f], median: $median%.2f, avg: $avg%.2f, p75: $p75%.2f, p95: $p95%.2f, p99: $p99%.2f, p99.95: ${`p99.95`}%.2f, p99.99: ${`p99.99`}%.2f, sum: $sum%.2f"
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
      val p99 = percentile.evaluate(99)
      val `p99.95` = percentile.evaluate(99.95)
      val `p99.99` = percentile.evaluate(99.99)
      val stop = System.currentTimeMillis()
      val sum = pq.sum
      Statistics(
        numOfValues = pq.size,
        measureTimeMs = stop - start,
        minValue = min,
        maxValue = max,
        median = median,
        avg = sum / pq.size,
        p75 = p75,
        p95 = p95,
        p99 = p99,
        `p99.95` = `p99.95`,
        `p99.99` = `p99.99`,
        sum = sum
      )
    } else {
      Statistics(
        numOfValues = 0,
        measureTimeMs = 0,
        minValue = Double.NaN,
        maxValue = Double.NaN,
        median = Double.NaN,
        avg = Double.NaN,
        p75 = Double.NaN,
        p95 = Double.NaN,
        p99 = Double.NaN,
        `p99.95` = Double.NaN,
        `p99.99` = Double.NaN,
        sum = 0.0
      )
    }
  }
}
