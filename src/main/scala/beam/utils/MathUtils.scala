package beam.utils
import scala.collection.JavaConverters._

/**
  * Created by sfeygin on 4/10/17.
  */
object MathUtils {

  /**
    * Safely round numbers using a specified scale
    *
    * @param inVal value to round
    * @param scale number of decimal places to use
    * @return
    */
  def roundDouble(inVal: Double, scale: Int = 3): Double = {
    BigDecimal(inVal).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  /**
    * Calculates the median for the given collection of doubles
    * @param list the list of data
    * @return median of the given list
    */
  def median(list: java.util.List[java.lang.Double]): Double = {
    if (list.isEmpty) {
      0
    } else {
      val sortedList = list.asScala.sortWith(_ < _)
      list.size match {
        case 1                   => sortedList.head
        case odd if odd % 2 == 1 => sortedList(odd / 2)
        case even if even % 2 == 0 =>
          val (l, h) = sortedList splitAt even / 2
          (l.last + h.head) / 2
        case _ => 0
      }
    }
  }

  def isNumberPowerOfTwo(number: Int): Boolean = {
    number > 0 && ((number & (number - 1)) == 0)
  }

  /**
    * Sums together things in log space.
    * @return log(\sum exp(a_i))
    * Taken from Sameer Singh
    * https://github.com/sameersingh/scala-utils/blob/master/misc/src/main/scala/org/sameersingh/utils/misc/Math.scala
    */

  def logSumExp(a: Double, b: Double): Double = {
    val output: Double =
      if (a.isNegInfinity) b
      else if (b.isNegInfinity) a
      else if (a < b) b + math.log(1 + math.exp(a - b))
      else a + math.log(1 + math.exp(b - a))
    output
  }

  /**
    * Sums together things in log space.
    * @return log(\sum exp(a_i))
    */
  def logSumExp(a: Double, b: Double, c: Double*): Double = {
    logSumExp(Array(a, b) ++ c)
  }

  /**
    * Sums together things in log space.
    * @return log(\sum exp(a_i))
    */
  def logSumExp(iter: Iterator[Double], max: Double): Double = {
    var accum = 0.0
    while (iter.hasNext) {
      val b = iter.next
      if (!b.isNegInfinity)
        accum += math.exp(b - max)
    }
    max + math.log(accum)
  }

  /**
    * Sums together things in log space.
    * @return log(\sum exp(a_i))
    */
  def logSumExp(a: Iterable[Double]): Double = {
    a.size match {
      case 0 => Double.NegativeInfinity;
      case 1 => a.head
      case 2 => logSumExp(a.head, a.last);
      case _ =>
        val m = a.max
        if (m.isInfinite) m
        else {
          var i = 0
          var accum = 0.0
          a.foreach { x =>
            accum += math.exp(x - m)
            i += 1;
          }
          m + math.log(accum)
        }
    }
  }

  def roundToFraction(x: Double, fraction: Long): Double = (x * fraction).round.toDouble / fraction

  def formatBytes(v: Long): String = {
    if (v < 1024) return v + " B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(v)) / 10
    "%.1f %sB".format(v.toDouble / (1L << (z * 10)), " KMGTPE".charAt(z))
  }
}
