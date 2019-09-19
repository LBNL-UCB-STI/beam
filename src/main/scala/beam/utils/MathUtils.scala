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

}
