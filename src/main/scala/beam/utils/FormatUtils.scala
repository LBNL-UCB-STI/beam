package beam.utils

/**
  * Created by sfeygin on 4/6/17.
  */
object FormatUtils {

  import java.text.DecimalFormat

  private val NO_ROUNDING_ERROR = -1.0
  private val ROUNDING_ERROR = 1e-5
  private val ONE_ROUNDING_ERROR = 1.0 - ROUNDING_ERROR

  class FormatType(align: String, fmtStr: String, roundingError: Double = NO_ROUNDING_ERROR) {
    val fmt = new DecimalFormat(fmtStr)

    /**
      * Format a floating point value
      *
      * @param x floating point value
      */
    def toString(x: Double): String = s"${String.format(align, fmt.format(conv(x)))}"

    /**
      * Format an integer value
      *
      * @param n integer
      */
    def toString(n: Int): String = String.format(align, n.toString)

    /**
      * Format a string
      *
      * @param s characters string
      */
    def toString(s: String): String = String.format(align, s)

    /**
      * Format a parameterized type T
      *
      * @param t value of type T to format
      */
    def toString[T](t: T): String = String.format(align, t.toString)

    /*
     * Applies a rounding error scheme is the
     */
    private def conv(x: Double): Double = roundingError match {
      case NO_ROUNDING_ERROR => x
      case ROUNDING_ERROR =>
        val xFloor = x.floor
        if (x - xFloor < ROUNDING_ERROR) xFloor
        else if (x - xFloor > ONE_ROUNDING_ERROR) xFloor + 1.0
        else x
    }
  }

  /**
    * Short format as 6.004912491 => 6.004
    */
  final object SHORT extends FormatType("%8s", "#,##0.000")

  /**
    * Short format with rounding error adjustment as 6.0049124 => 6.000
    */
  final object SHORT_ROUND extends FormatType("%8s", "#,##0.000", ROUNDING_ERROR)

  /**
    * Medium format as 6.004912491 => 6.00491
    */
  final object MEDIUM extends FormatType("%11s", "#,##0.00000")

  /**
    * Medium format as 6.004912491 => 6.004913491
    */
  final object LONG extends FormatType("%15s", "#,##0.00000000")

}
