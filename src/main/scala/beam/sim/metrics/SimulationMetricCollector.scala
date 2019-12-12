package beam.sim.metrics

import java.time.{LocalDate, LocalDateTime, ZoneId}
import java.util.Collections
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics._
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.influxdb.{BatchOptions, InfluxDB, InfluxDBFactory}
import org.influxdb.dto.Point

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

object SimulationMetricCollector {
  val defaultMetricValueName: String = "count"
  case class SimulationTime(seconds: Int) extends AnyVal {

    def hours: Long =
      TimeUnit.SECONDS.toHours(seconds)
  }
}

trait SimulationMetricCollector {

  import SimulationMetricCollector._

  def write(
    metricName: String,
    time: SimulationTime,
    values: Map[String, Double] = Map.empty,
    tags: Map[String, String] = Map.empty,
    level: MetricLevel = ShortLevel
  ): Unit

  def writeGlobal(
    metricName: String,
    metricValue: Double,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit = {
    write(metricName, SimulationTime(0), Map(defaultMetricValueName -> metricValue), tags, level)
  }

  def writeGlobalJava(
    metricName: String,
    metricValue: Double,
    tags: java.util.Map[String, String]
  ): Unit = {
    write(metricName, SimulationTime(0), Map(defaultMetricValueName -> metricValue), tags.asScala.toMap, ShortLevel)
  }

  def writeGlobalJava(
    metricName: String,
    metricValue: Double,
    level: MetricLevel = ShortLevel,
    tags: java.util.Map[String, String] = Collections.EMPTY_MAP.asInstanceOf[java.util.Map[String, String]]
  ): Unit = {
    write(metricName, SimulationTime(0), Map(defaultMetricValueName -> metricValue), tags.asScala.toMap, level)
  }

  def writeIteration(
    metricName: String,
    time: SimulationTime,
    metricValue: Double = 1.0,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit = {
    write(metricName, time, Map(defaultMetricValueName -> metricValue), tags, level)
  }

  def writeIterationjava(
    metricName: String,
    seconds: Int,
    metricValue: Double = 1.0,
    level: MetricLevel,
    tags: java.util.Map[String, String] = Collections.EMPTY_MAP.asInstanceOf[java.util.Map[String, String]]
  ): Unit = {
    write(metricName, SimulationTime(seconds), Map(defaultMetricValueName -> metricValue), tags.asScala.toMap, level)
  }

  def increment(name: String, time: SimulationTime, level: MetricLevel): Unit = {
    if (isRightLevel(level)) {
      writeIteration(name, time = time, level = level)
    }
  }

  def decrement(name: String, time: SimulationTime, level: MetricLevel): Unit = {
    if (isRightLevel(level)) {
      writeIteration(name, time = time, -1.0, level = level)
    }
  }

  def clear(): Unit
  def close(): Unit
}

// To use in tests as a mock
object NoOpSimulationMetricCollector extends SimulationMetricCollector {

  override def clear(): Unit = {}

  override def close(): Unit = {}

  override def write(
    metricName: String,
    time: SimulationTime,
    values: Map[String, Double],
    tags: Map[String, String],
    level: MetricLevel
  ): Unit = {}
}

class InfluxDbSimulationMetricCollector @Inject()(beamCfg: BeamConfig)
    extends SimulationMetricCollector
    with LazyLogging {
  private val cfg = beamCfg.beam.sim.metric.collector.influxDbSimulationMetricCollector
  private val metricToLastSeenTs: ConcurrentHashMap[String, Long] = new ConcurrentHashMap[String, Long]()
  private val step: Long = TimeUnit.MICROSECONDS.toNanos(1L)
  private val todayBeginningOfDay: LocalDateTime = LocalDate.now().atStartOfDay()

  private val todayAsNanos: Long = {
    val todayInstant = todayBeginningOfDay.toInstant(ZoneId.systemDefault().getRules.getOffset(todayBeginningOfDay))
    val tsNano = TimeUnit.MILLISECONDS.toNanos(todayInstant.toEpochMilli)
    logger.info(s"Today is $todayBeginningOfDay, toEpochMilli: ${todayInstant.toEpochMilli} ms or $tsNano ns")
    tsNano
  }

  val maybeInfluxDB: Option[InfluxDB] = {
    try {
      val db = InfluxDBFactory.connect(cfg.connectionString)
      db.setDatabase(cfg.database)
      db.enableBatch(BatchOptions.DEFAULTS)
      logger.info(s"Connected to InfluxDB at ${cfg.connectionString}, database: ${cfg.database}")
      Some(db)
    } catch {
      case NonFatal(t: Throwable) =>
        logger.error(
          s"Could not connect to InfluxDB at ${cfg.connectionString}, database: ${cfg.database}: ${t.getMessage}",
          t
        )
        None
    }
  }

  override def write(
    metricName: String,
    time: SimulationTime,
    values: Map[String, Double],
    tags: Map[String, String],
    level: MetricLevel
  ): Unit = {
    if (isRightLevel(level)) {
      val rawPoint = Point
        .measurement(metricName)
        .time(influxTime(metricName, time.seconds), TimeUnit.NANOSECONDS)
        .tag("simulation-hour", time.hours.toString)

      val withFields = values.foldLeft(rawPoint) {
        case (p, (n, v)) => p.addField(n, v)
      }

      val withDefaultTags = defaultTags.foldLeft(withFields) {
        case (p, (k, v)) => p.tag(k, v)
      }

      val withOtherTags = tags.foldLeft(withDefaultTags) {
        case (p, (k, v)) => p.tag(k, v)
      }

      maybeInfluxDB.foreach(_.write(withOtherTags.build()))
    }
  }

  override def clear(): Unit = {
    metricToLastSeenTs.clear()
  }

  override def close(): Unit = {
    Try(maybeInfluxDB.foreach(_.flush()))
    Try(maybeInfluxDB.foreach(_.close()))
  }

  private def influxTime(metricName: String, simulationTimeSeconds: Long): Long = {
    val tsNano = todayAsNanos + TimeUnit.SECONDS.toNanos(simulationTimeSeconds)
    getNextInfluxTs(metricName, tsNano)
  }

  private def getNextInfluxTs(metricName: String, tsNano: Long): Long = {
    // See https://github.com/influxdata/influxdb/issues/2055
    // Points in a series can not have the same exact time (down to nanosecond). A series is defined by the measurement and tagset.
    // We store the last seen `tsNano` and add up `step` in case if it is already there
    val key = s"$metricName:$tsNano"
    val prevTs = metricToLastSeenTs.getOrDefault(key, tsNano)
    val newTs = prevTs + step
    metricToLastSeenTs.put(key, newTs)
    newTs
  }
}
