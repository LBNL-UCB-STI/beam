package beam.utils.metrics

import java.time.Duration

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.{Response, newFixedLengthResponse}
import kamon.Kamon
import kamon.metric._
import kamon.module.{MetricReporter, Module, ModuleFactory}
import kamon.prometheus.{PrometheusReporter, ScrapeDataBuilder}
import org.slf4j.LoggerFactory

import scala.util.Try

class BeamPrometheusReporter(configPath: String) extends MetricReporter {
  import PrometheusReporter.Settings.{environmentTags, readSettings}

  private val _logger = LoggerFactory.getLogger(classOf[BeamPrometheusReporter])
  private var _embeddedHttpServer: Option[EmbeddedHttpServer] = None
  private val _snapshotAccumulator = PeriodSnapshot.accumulator(Duration.ofDays(365 * 5), Duration.ZERO)

  @volatile private var _preparedScrapeData: String =
    "# The kamon-prometheus module didn't receive any data just yet.\n"


  implicit val system: ActorSystem = ActorSystem("BeamPrometheusReporter")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val httpClient: HttpClient = new HttpClient()

  def this() =
    this("kamon.prometheus")

  {
    val initialSettings = readSettings(Kamon.config().getConfig(configPath))
    if(initialSettings.startEmbeddedServer)
      startEmbeddedServer(initialSettings)
  }

  override def stop(): Unit = {
    stopEmbeddedServer()
    Try(materializer.shutdown())
    Try(system.terminate())
  }

  override def reconfigure(newConfig: Config): Unit = {
    val config = readSettings(newConfig.getConfig(configPath))

    stopEmbeddedServer()
    if(config.startEmbeddedServer) {
      startEmbeddedServer(config)
    }
  }

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    _snapshotAccumulator.add(snapshot)
    val currentData = _snapshotAccumulator.peek()
    val reporterConfiguration = readSettings(Kamon.config().getConfig(configPath))
    val scrapeDataBuilder = new ScrapeDataBuilder(reporterConfiguration, environmentTags(reporterConfiguration))

    scrapeDataBuilder.appendCounters(currentData.counters)
    scrapeDataBuilder.appendGauges(currentData.gauges)
    scrapeDataBuilder.appendHistograms(currentData.histograms)
    scrapeDataBuilder.appendHistograms(currentData.timers)
    scrapeDataBuilder.appendHistograms(currentData.rangeSamplers)
    _preparedScrapeData = scrapeDataBuilder.build()

    httpClient.postString("http://localhost:9091/metrics/job/some_job", _preparedScrapeData).foreach { case resp =>
      _logger.info(resp.toString())
    }(system.dispatcher)
  }

  def scrapeData(): String =
    _preparedScrapeData

  class EmbeddedHttpServer(hostname: String, port: Int) extends NanoHTTPD(hostname, port) {
    override def serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = {
      newFixedLengthResponse(Response.Status.OK, "text/plain; version=0.0.4; charset=utf-8", scrapeData())
    }
  }

  private def startEmbeddedServer(config: PrometheusReporter.Settings): Unit = {
    val server = new EmbeddedHttpServer(config.embeddedServerHostname, config.embeddedServerPort)
    server.start()

    _logger.info(s"Started the embedded HTTP server on http://${config.embeddedServerHostname}:${config.embeddedServerPort}")
    _embeddedHttpServer = Some(server)
  }

  private def stopEmbeddedServer(): Unit =
    _embeddedHttpServer.foreach(_.stop())
}

object BeamPrometheusReporter {

  class Factory extends ModuleFactory {
    override def create(settings: ModuleFactory.Settings): Module =
      new BeamPrometheusReporter()
  }

  def create(): BeamPrometheusReporter = {
    val defaultConfigPath = "kamon.prometheus"
    new BeamPrometheusReporter()
  }
}