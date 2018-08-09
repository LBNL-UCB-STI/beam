package beam.utils

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
  def roundDouble(inVal: Double, scale: Int): Double = {
    BigDecimal(inVal).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toDouble
  }
}
