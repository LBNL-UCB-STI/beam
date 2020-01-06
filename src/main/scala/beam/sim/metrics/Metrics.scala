package beam.sim.metrics

import akka.util.Helpers.toRootLowerCase
import beam.utils.FileUtils
import kamon.metric.Timer.Started
import org.influxdb.{InfluxDB, InfluxDBFactory}
import org.influxdb.BatchOptions

import java.time.{LocalDate, ZoneId}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

object Metrics {

  var level: String = "off"

  var runName: String = "beam"

  @volatile
  var iterationNumber: Int = 0

  val timers: ConcurrentHashMap[String, Started] = new ConcurrentHashMap[String, Started]()

  def addTimer(name: String, started: Started): Unit = {
    timers.putIfAbsent(name, started)
  }

  def getTimer(name: String): Option[Started] = {
    Option(timers.get(name))
  }

  def defaultTags: Map[String, String] =
    Map(
      "run-name"        -> runName,
      "unique-run-name" -> s"${FileUtils.runStartTime}_$runName",
      "iteration-num"   -> s"$iterationNumber"
    )

  private def metricLevel: MetricLevel = levelForOrOff(level)

  /**
    * Marker trait for annotating MetricLevel, which must be Int after erasure.
    */
  final case class MetricLevel(asInt: Int) extends AnyVal {
    @inline def >=(other: MetricLevel): Boolean = asInt >= other.asInt

    @inline def <=(other: MetricLevel): Boolean = asInt <= other.asInt

    @inline def >(other: MetricLevel): Boolean = asInt > other.asInt

    @inline def <(other: MetricLevel): Boolean = asInt < other.asInt
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
    case "off"     => Some(OffLevel)
    case "short"   => Some(ShortLevel)
    case "regular" => Some(RegularLevel)
    case "verbose" => Some(VerboseLevel)
    case _         => None
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
  def isMetricsEnable: Boolean = metricLevel != OffLevel

  def isRightLevel(level: MetricLevel): Boolean = level <= metricLevel && level != OffLevel
}
