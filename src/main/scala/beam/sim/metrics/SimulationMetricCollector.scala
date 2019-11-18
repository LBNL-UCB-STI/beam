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
  case class SimulationTime(time: Int) extends AnyVal
}

trait SimulationMetricCollector {

  def writeGlobal(
    metricName: String,
    metricValue: Double,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit

  def writeGlobalJava(
    metricName: String,
    metricValue: Double
  ): Unit = {
    writeGlobal(metricName, metricValue, ShortLevel, Map.empty)
  }

  def writeGlobalJava(
    metricName: String,
    metricValue: Double,
    tags: java.util.Map[String, String]
  ): Unit = {
    writeGlobal(metricName, metricValue, ShortLevel, tags.asScala.toMap)
  }

  def writeGlobalJava(
    metricName: String,
    metricValue: Double,
    level: MetricLevel = ShortLevel,
    tags: java.util.Map[String, String] = Collections.EMPTY_MAP.asInstanceOf[java.util.Map[String, String]]
  ): Unit = {
    writeGlobal(metricName, metricValue, level, tags.asScala.toMap)
  }

  def writeIteration(
    metricName: String,
    time: SimulationTime,
    metricValue: Double = 1.0,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit

  def writeIterationjava(
    name: String,
    time: Int,
    times: Double = 1.0,
    level: MetricLevel,
    tags: java.util.Map[String, String] = Collections.EMPTY_MAP.asInstanceOf[java.util.Map[String, String]]
  ): Unit = {
    writeIteration(name, SimulationTime(time), times, level, tags.asScala.toMap)
  }

  def increment(name: String, time: SimulationTime, level: MetricLevel): Unit = {
    if (isRightLevel(level)) {
      writeIteration(name, time = time, 1.0, level = level)
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
  override def writeIteration(
    name: String,
    time: SimulationTime,
    times: Double,
    level: MetricLevel,
    tags: Map[String, String]
  ): Unit = {}

  override def writeGlobal(
    metricName: String,
    metricValue: Double,
    level: MetricLevel,
    tags: Map[String, String]
  ): Unit = {}

  override def clear(): Unit = {}

  override def close(): Unit = {}
}

class InfluxDbSimulationMetricCollector @Inject()(beamCfg: BeamConfig)
    extends SimulationMetricCollector
    with LazyLogging {
  private val cfg = beamCfg.beam.sim.metric.collector.influxDbSimulationMetricCollector
  private val metricToLastSeenTs: ConcurrentHashMap[String, Long] = new ConcurrentHashMap[String, Long]()
  private val step: Long = TimeUnit.MICROSECONDS.toNanos(1L)
  private val todayAsNanos: Long = {
    val todayBeginningOfDay: LocalDateTime = LocalDate.now().atStartOfDay()
    val todayInstant = todayBeginningOfDay.toInstant(ZoneId.systemDefault().getRules.getOffset(todayBeginningOfDay))
    val tsNano = TimeUnit.MILLISECONDS.toNanos(todayInstant.toEpochMilli)
    logger.info(s"Today is $todayBeginningOfDay, toEpochMilli: ${todayInstant.toEpochMilli} ms or $tsNano ns")
    tsNano
  }

  logger.info(s"Trying to create connection with ${cfg.connectionString}, database: ${cfg.database}")

  val maybeInfluxDB: Option[InfluxDB] = {
    try {
      val db = InfluxDBFactory.connect(cfg.connectionString)
      db.setDatabase(cfg.database)
      db.enableBatch(BatchOptions.DEFAULTS)
      Some(db)
    } catch {
      case NonFatal(t: Throwable) =>
        logger.error(s"Could not connect to InfluxDB at ${cfg.connectionString}: ${t.getMessage}", t)
        None
    }
  }

  override def writeGlobal(
    metricName: String,
    metricValue: Double,
    level: MetricLevel,
    tags: Map[String, String]
  ): Unit = {
    if (isRightLevel(level)) {
      val rawPoint = Point
        .measurement(metricName)
        .addField("count", metricValue)
        .time(influxTime(metricName, TimeUnit.HOURS.toSeconds(Metrics.iterationNumber)), TimeUnit.NANOSECONDS)

      val withDefaultTags = defaultTags.foldLeft(rawPoint) {
        case (p, (k, v)) =>
          p.tag(k, v)
      }

      val point = tags
        .foldLeft(withDefaultTags) {
          case (p, (k, v)) =>
            p.tag(k, v)
        }
        .build()

      maybeInfluxDB.foreach(_.write(point))
    }
  }

  override def writeIteration(
    name: String,
    time: SimulationTime,
    times: Double = 1.0,
    level: MetricLevel = ShortLevel,
    tags: Map[String, String] = Map.empty
  ): Unit = {
    if (isRightLevel(level)) {
      val rawPoint = Point
        .measurement(name)
        .addField("count", times)
        .time(influxTime(name, time.time), TimeUnit.NANOSECONDS)
      val withDefaultTags = defaultTags.foldLeft(rawPoint) {
        case (p, (k, v)) =>
          p.tag(k, v)
      }
      val withOtherTags = tags.foldLeft(withDefaultTags) {
        case (p, (k, v)) =>
          p.tag(k, v)
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
    val key = s"${metricName}:$tsNano"
    val prevTs = metricToLastSeenTs.getOrDefault(key, tsNano)
    val newTs = prevTs + step
    metricToLastSeenTs.put(key, newTs)
    newTs
  }
}
