package beam.integration

import akka.actor.ActorSystem
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.csv.GenericCsvReader
import beam.utils.{FileUtils, LoggingUtil}
import ch.qos.logback.classic.util.ContextInitializer
import com.google.inject.Injector
import com.typesafe.config.ConfigFactory
import org.matsim.core.scenario.MutableScenario
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpecLike

import java.io.{Closeable, File}

case class LinkStatsCsvRow(link: Int, volume: Double)

object LinkStatsCsvRow {

  def toCsvRow(rec: java.util.Map[String, String]): LinkStatsCsvRow =
    LinkStatsCsvRow(rec.get("link").toInt, rec.get("volume").toDouble)
}

class NetworkRelaxationSpec extends AnyWordSpecLike with BeamHelper {
  val lastIteration = 15
  System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback-test.xml")

  "Network relaxation" should {
    "pass" in {
      val config = ConfigFactory
        .parseFile(new File("test/input/network-relaxation-scenario/beam.conf"))
        .resolve()
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSimConf()
      val beamConfig = BeamConfig(config)

      val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(beamConfig, matsimConfig)
      val scenario: MutableScenario = scenarioBuilt
      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val injector: Injector = buildInjector(config, beamConfig, scenario, beamScenario)
      implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
      val beamServices: BeamServices = buildBeamServices(injector)

      LoggingUtil.initLogger(outputDir, beamConfig.beam.logger.keepConsoleAppenderOn)

      runBeam(
        beamServices,
        scenario,
        beamScenario,
        outputDir,
        true
      )

      val linkStats = new File(outputDir, s"ITERS/it.$lastIteration/$lastIteration.linkstats.csv.gz")

      val (iter: Iterator[LinkStatsCsvRow], toClose: Closeable) =
        GenericCsvReader.readAs[LinkStatsCsvRow](linkStats.toString, LinkStatsCsvRow.toCsvRow, _ => true)
      val result =
        try {
          iter.toVector
        } finally {
          toClose.close()
        }

      val routes = List(
        Set(0, 26, 24),
        Set(4, 28, 22),
        Set(2, 30, 20),
        Set(6, 32, 8)
      )

      val sums = routes.map(route => result.filter(row => route.contains(row.link)).map(_.volume).sum)

      sums.foreach(_ should be > 500.0)
    }
  }
}
