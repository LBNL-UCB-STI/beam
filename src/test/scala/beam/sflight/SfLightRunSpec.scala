package beam.sflight

import java.nio.file.Paths

import beam.agentsim.events.ModeChoiceEvent
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"
  private val METRICS_LEVEL = "beam.metrics.level"
  private val KAMON_INFLUXDB = "kamon.modules.kamon-influxdb.auto-start"

  private var baseConf: Config = _
  private var totalIterations: Int = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    val confPath = configMap.getWithDefault("config", "test/input/sf-light/sf-light-5k.conf")
    totalIterations = configMap.getWithDefault("iterations", "1").toInt
    logger.info(s"Starting test with config [$confPath] and iterations [$totalIterations]")
    baseConf = testConfig(confPath)
      .withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))
    baseConf.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)
  }

  "SF Light" must {
    "run without error and at least one person chooses car mode" ignore {
      val config = testConfig("test/input/sf-light/sf-light-1k.conf")
        .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSamConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig = BeamConfig(config)

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      val networkCoordinator = new NetworkCoordinator(beamConfig)
      networkCoordinator.loadNetwork()
      networkCoordinator.convertFrequenciesToTrips()
      scenario.setNetwork(networkCoordinator.network)
      var nCarTrips = 0
      val injector = org.matsim.core.controler.Injector.createInjector(
        scenario.getConfig,
        new AbstractModule() {
          override def install(): Unit = {
            install(module(config, scenario, networkCoordinator))
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
      val conf = baseConf
        .withValue(METRICS_LEVEL, ConfigValueFactory.fromAnyRef("off"))
        .withValue(KAMON_INFLUXDB, ConfigValueFactory.fromAnyRef("no"))
        .resolve()
      val (_, output) = runBeamWithConfig(conf)

      val outDir = Paths.get(output).toFile

      val itrDir = Paths.get(output, ITERS_DIR).toFile

      outDir should be a 'directory
      outDir.list should not be empty
      outDir.list should contain(ITERS_DIR)
      itrDir.list should have length totalIterations
      itrDir
        .listFiles()
        .foreach(
          itr => exactly(1, itr.list) should endWith(".events.csv").or(endWith(".events.csv.gz"))
        )
    }
  }

}
