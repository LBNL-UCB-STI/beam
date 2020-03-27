package beam.sim.metrics

import java.time.{LocalDate, LocalDateTime, ZoneId}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import beam.sim.config.BeamConfig
import beam.sim.metrics.Metrics._
import beam.sim.metrics.SimulationMetricCollector.SimulationTime
import com.typesafe.scalalogging.LazyLogging
import javax.inject.Inject
import org.influxdb.{BatchOptions, InfluxDB, InfluxDBFactory}
import org.influxdb.dto.Point

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable}
import scala.util.Try
import scala.util.control.NonFatal

object SimulationMetricCollector {
  val defaultMetricName: String = "count"

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
    overwriteIfExist: Boolean = false
  ): Unit

  def writeStr(
    metricName: String,
    time: SimulationTime,
    values: Map[String, String] = Map.empty,
    tags: Map[String, String] = Map.empty,
    overwriteIfExist: Boolean = false
  ): Unit

  def writeJava(
    metricName: String,
    time: Double,
    values: java.util.Map[String, Double],
    tags: java.util.Map[String, String],
    overwriteIfExist: Boolean = false
  ): Unit = write(metricName, SimulationTime(time.toInt), values.asScala.toMap, tags.asScala.toMap, overwriteIfExist)

  def writeIterationMapPoint(
    metricName: String,
    eventTime: Double,
    value: Double,
    lat: Double,
    lon: Double,
    overwriteIfExist: Boolean = false
  ): Unit =
    write(
      metricName,
      SimulationTime(eventTime.toInt),
      Map("value" -> value, "lat" -> lat, "lon" -> lon),
      overwriteIfExist = overwriteIfExist
    )

  def writeGlobal(
    metricName: String,
    metricValue: Double,
    tags: Map[String, String] = Map.empty,
    overwriteIfExist: Boolean = false
  ): Unit = {
    write(metricName, SimulationTime(0), Map(defaultMetricName -> metricValue), tags, overwriteIfExist)
  }

  def writeGlobalJava(
    metricName: String,
    metricValue: Double,
    tags: java.util.Map[String, String],
    overwriteIfExist: Boolean = false
  ): Unit = {
    write(
      metricName,
      SimulationTime(0),
      Map(defaultMetricName -> metricValue),
      tags.asScala.toMap,
      overwriteIfExist
    )
  }

  def writeIteration(
    metricName: String,
    time: SimulationTime,
    metricValue: Double = 1.0,
    tags: Map[String, String] = Map.empty,
    overwriteIfExist: Boolean = false
  ): Unit = {
    write(metricName, time, Map(defaultMetricName -> metricValue), tags, overwriteIfExist)
  }

  def writeIterationJava(
    metricName: String,
    seconds: Int,
    metricValue: Double,
    tags: java.util.Map[String, String],
    overwriteIfExist: Boolean = false
  ): Unit = {
    write(
      metricName,
      SimulationTime(seconds),
      Map(defaultMetricName -> metricValue),
      tags.asScala.toMap,
      overwriteIfExist
    )
  }

  def metricEnabled(metricName: String): Boolean

  def clear(): Unit

  def close(): Unit
}

// To use in tests as a mock
object NoOpSimulationMetricCollector extends SimulationMetricCollector {

  override def write(
    metricName: String,
    time: SimulationTime,
    values: Map[String, Double],
    tags: Map[String, String],
    overwriteIfExist: Boolean = false
  ): Unit = {}

  def writeStr(
    metricName: String,
    time: SimulationTime,
    values: Map[String, String],
    tags: Map[String, String],
    overwriteIfExist: Boolean
  ): Unit = {}

  override def clear(): Unit = {}

  override def close(): Unit = {}

  def metricEnabled(metricName: String): Boolean = false
}

object InfluxDbSimulationMetricCollector {

  def getNextInfluxTs(
    metricToTsNano: ConcurrentHashMap[String, Long],
    metricName: String,
    tsNano: Long,
    delta: Long
  ): Long = {
    // See https://github.com/influxdata/influxdb/issues/2055
    // Points in a series can not have the same exact time (down to nanosecond). A series is defined by the measurement and tagset.
    // We store the last seen `tsNano` and add up `step` in case if it is already there

    val mappingFunction = new java.util.function.BiFunction[String, Long, Long]() {
      override def apply(key: String, oldValue: Long): Long = {
        if (oldValue < tsNano) {
          tsNano + delta
        } else {
          oldValue + delta
        }
      }
    }

    val key = s"$metricName:$tsNano"
    val newTs: Long = metricToTsNano.compute(key, mappingFunction)
    newTs
  }
}

class InfluxDbSimulationMetricCollector @Inject()(beamCfg: BeamConfig)
    extends SimulationMetricCollector
    with LazyLogging {

  import InfluxDbSimulationMetricCollector._

  private val cfg = beamCfg.beam.sim.metric.collector.influxDbSimulationMetricCollector
  private val metricToLastSeenTs: ConcurrentHashMap[String, Long] = new ConcurrentHashMap[String, Long]()
  private val step: Long = TimeUnit.MICROSECONDS.toNanos(1L)
  private val todayBeginningOfDay: LocalDateTime = LocalDate.now().atStartOfDay()

  private val disabledMetrics: mutable.HashSet[String] = new mutable.HashSet[String]()

  private val enabledMetrics: immutable.HashSet[String] = {
    val metrics = beamCfg.beam.sim.metric.collector.metrics
      .split(',')
      .map(entry => entry.trim)

    logger.info(s"Enabled metrics: ${metrics.mkString(",")}")
    scala.collection.immutable.HashSet(metrics: _*)
  }

  def metricEnabled(metricName: String): Boolean = {
    val isEnabled = enabledMetrics.contains(metricName)
    if (!isEnabled) {
      disabledMetrics.add(metricName)
    }

    isEnabled
  }

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
      db.ping()
      logger.info(s"Connected to InfluxDB at ${cfg.connectionString}, database: ${cfg.database}")
      Some(db)
    } catch {
      case NonFatal(t: Throwable) =>
        logger.warn(
          s"Could not connect to InfluxDB at ${cfg.connectionString}, database: ${cfg.database}. Error: ${t.getMessage}"
        )
        None
    }
  }

  override def write(
    metricName: String,
    time: SimulationTime,
    values: Map[String, Double],
    tags: Map[String, String],
    overwriteIfExist: Boolean
  ): Unit = {
    if (metricEnabled(metricName)) {
      val rawPoint = Point
        .measurement(metricName)
        .time(influxTime(metricName, time.seconds, overwriteIfExist), TimeUnit.NANOSECONDS)
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

  override def writeStr(
    metricName: String,
    time: SimulationTime,
    values: Map[String, String],
    tags: Map[String, String],
    overwriteIfExist: Boolean
  ): Unit = {
    if (metricEnabled(metricName)) {
      val rawPoint = Point
        .measurement(metricName)
        .time(influxTime(metricName, time.seconds, overwriteIfExist), TimeUnit.NANOSECONDS)
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

    logger.info(s"Following metrics was disabled: ${disabledMetrics.mkString(",")}")
    logger.info(s"Following metrics was enabled: ${enabledMetrics.mkString(",")}")
  }

  private def influxTime(metricName: String, simulationTimeSeconds: Long, overwriteIfExist: Boolean): Long = {
    val tsNano = todayAsNanos + TimeUnit.SECONDS.toNanos(simulationTimeSeconds)
    // influxDB overrides values when they have the same timestamp.
    // sometimes that behaviour is handy
    if (overwriteIfExist) {
      tsNano
    } else {
      getNextInfluxTs(metricToLastSeenTs, metricName, tsNano, step)
    }
  }
}
