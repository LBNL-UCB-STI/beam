package beam.sflight

import java.nio.file.Paths
import beam.agentsim.events.ModeChoiceEvent
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{Assertion, BeforeAndAfterAllConfigMap, ConfigMap}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import java.io.File

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"
  private val METRICS_LEVEL = "beam.metrics.level"
  private val KAMON_INFLUXDB = "kamon.modules.kamon-influxdb.auto-start"

  private var configMap: ConfigMap = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    this.configMap = configMap
  }

  "SF Light" must {
    "run 0.5k scenario for one iteration and at least one person chooses car mode" in {
      val config = ConfigFactory
        .parseString(
          """
          |beam.actorSystemName = "SfLightRunSpec"
          |beam.outputs.events.fileOutputFormats = xml
          |beam.agentsim.lastIteration = 0
        """.stripMargin
        )
        .withFallback(testConfig("test/input/sf-light/sf-light-0.5k.conf"))
        .resolve()
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig = BeamConfig(config)

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val beamScenario = loadScenario(beamConfig)
      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      scenario.setNetwork(beamScenario.network)

      var nCarTrips = 0
      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        new AbstractModule() {
          override def install(): Unit = {
            install(module(config, beamConfig, scenario, beamScenario))
            addEventHandlerBinding().toInstance(new BasicEventHandler {
              override def handleEvent(event: Event): Unit = {
                event match {
                  case modeChoiceEvent: ModeChoiceEvent =>
                    if (modeChoiceEvent.getAttributes.get("mode").equals("car")) {
                      nCarTrips = nCarTrips + 1
                    }
                  case _ =>
                }
              }
            })
          }
        }
      )
      val services = injector.getInstance(classOf[BeamServices])
      DefaultPopulationAdjustment(services).update(scenario)
      val controler = services.controler
      controler.run()
      assert(nCarTrips > 1)
    }

    "run 5k(default) scenario for one iteration" taggedAs (Periodic, ExcludeRegular) ignore {
      val confPath = configMap.getWithDefault("config", "test/input/sf-light/sf-light-5k.conf")
      val totalIterations = configMap.getWithDefault("iterations", "1").toInt
      logger.info(s"Starting test with config [$confPath] and iterations [$totalIterations]")
      val baseConf = testConfig(confPath)
        .resolve()
        .withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))
      baseConf.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)
      val conf = baseConf
        .withValue(METRICS_LEVEL, ConfigValueFactory.fromAnyRef("off"))
        .withValue(KAMON_INFLUXDB, ConfigValueFactory.fromAnyRef("no"))
        .resolve()
      val (_, output, _) = runBeamWithConfig(conf)

      val outDir = Paths.get(output).toFile

      val itrDir = Paths.get(output, ITERS_DIR).toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain(ITERS_DIR)
      itrDir.list should have length totalIterations
      itrDir
        .listFiles()
        .foreach(directoryHasOnlyOneEventsFile)
    }
  }

  private def directoryHasOnlyOneEventsFile(itr: File): Assertion = {
    assertResult(1) {
      itr.list.count(fileName => fileName.endsWith(".events.csv") || fileName.endsWith(".events.csv.gz"))
    }
  }
}
