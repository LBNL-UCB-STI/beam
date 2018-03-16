package beam.sim.metrics

import akka.util.Helpers.toRootLowerCase

object Metrics {
  var level: String = "off"

  private def metricLevel: MetricLevel = levelForOrOff(level)

  /**
    * Marker trait for annotating MetricLevel, which must be Int after erasure.
    */
  final case class MetricLevel(asInt: Int) extends AnyVal {
    @inline final def >=(other: MetricLevel): Boolean = asInt >= other.asInt

    @inline final def <=(other: MetricLevel): Boolean = asInt <= other.asInt

    @inline final def >(other: MetricLevel): Boolean = asInt > other.asInt

    @inline final def <(other: MetricLevel): Boolean = asInt < other.asInt
  }


  /**
    * Metric level in numeric form, used when deciding whether a certain metric
    * statement should generate a log metric. Predefined levels are @Short (1)
    * to @Verbose (3).
    */
  final val ShortLevel = MetricLevel(1)
  final val RegularLevel = MetricLevel(2)
  final val VerboseLevel = MetricLevel(3)

  /**
    * Internal use only
    *
    */
  private final val OffLevel = MetricLevel(Int.MinValue)

  /**
    * Returns the MetricLevel associated with the given string,
    * valid inputs are upper or lowercase (not mixed) versions of:
    * "short", "regular" and "verbose"
    */
  def levelFor(s: String): Option[MetricLevel] = toRootLowerCase(s) match {
    case "off" ⇒ Some(OffLevel)
    case "short" ⇒ Some(ShortLevel)
    case "regular" ⇒ Some(RegularLevel)
    case "verbose" ⇒ Some(VerboseLevel)
    case _ ⇒ None
  }

  /**
    * Returns the MetricLevel associated with the given string,
    * valid inputs are upper or lowercase (not mixed) versions of:
    * "short", "regular" and "verbose", in case of invalid input it
    * return @OffLevel
    */
  def levelForOrOff(s: String): MetricLevel = levelFor(s).getOrElse(OffLevel)

  /**
    * Returns true if associated string is a valid level else false
    */
  def isMetricsEnable(): Boolean = metricLevel != OffLevel

  def isRightLevel(level: MetricLevel) = level <= metricLevel && level != OffLevel
}
