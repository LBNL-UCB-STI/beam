package beam.sflight

import java.nio.file.Paths

import beam.agentsim.agents.vehicles.VehicleCategory.Car
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class UrbanSimRunSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private val ITERS_DIR = "ITERS"
  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"
  private val METRICS_LEVEL = "beam.metrics.level"
  private val KAMON_INFLUXDB = "kamon.modules.kamon-influxdb.auto-start"

  private var configMap: ConfigMap = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    this.configMap = configMap
  }

  "UrbanSimRun" must {
    "run  urbansim-1k.conf" in {
      val confPath = configMap.getWithDefault("config", "test/input/sf-light/urbansim-1k.conf")
      val totalIterations = configMap.getWithDefault("iterations", "1").toInt
      val baseConf = testConfig(confPath)
        .resolve()
        .withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations - 1))
      baseConf.getInt(LAST_ITER_CONF_PATH) should be(totalIterations - 1)
      val conf = baseConf
        .withValue(METRICS_LEVEL, ConfigValueFactory.fromAnyRef("off"))
        .withValue(KAMON_INFLUXDB, ConfigValueFactory.fromAnyRef("no"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(conf)
      val matsimConfig = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig = BeamConfig(conf)

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig,
      )

      val listOfVehicleTypes = beamScenario.vehicleTypes.values.filter(_.vehicleCategory == Car).map(_.id.toString)
      val listOfPrivateVehicleTypes = beamScenario.privateVehicles.values
        .groupBy(_.beamVehicleType)
        .keys
        .filter(_.vehicleCategory == Car)
        .map(_.id.toString)
      listOfVehicleTypes should contain("Car-rh-only")
      listOfVehicleTypes should have size 5
      listOfPrivateVehicleTypes should not contain ("Car-rh-only")
      listOfPrivateVehicleTypes should have size 4

      val injector = buildInjector(conf, beamConfig, scenario, beamScenario)
      val services = injector.getInstance(classOf[BeamServices])

      val output = scenario.getConfig.controler().getOutputDirectory

      runBeam(
        services,
        scenario,
        beamScenario,
        output
      )

      //val (_, output) = runBeamWithConfig(conf)

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
      val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
      if (travelDistanceStats != null)
        travelDistanceStats.close()
    }
  }
}
