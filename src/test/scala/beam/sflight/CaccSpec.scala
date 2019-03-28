package beam.sflight

import scala.io.Source

import beam.analysis.plots.PersonTravelTimeAnalysis
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.{FileUtils, NetworkHelper, NetworkHelperImpl}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.config.Config
import org.matsim.core.controler.{AbstractModule, OutputDirectoryHierarchy}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

class CaccSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private var configMap: ConfigMap = _

  override def beforeAll(configMap: ConfigMap): Unit = {
    this.configMap = configMap
  }

  private def runSimulationAndReturnAvgCarTravelTimes(caccEnabled: Boolean): Double = {
    val config = ConfigFactory
      .parseString(s"""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.agentsim.lastIteration = 1
                     |beam.physsim.jdeqsim.cacc.enabled = $caccEnabled
                     |beam.agentsim.agents.vehicles.vehiclesFilePath = $${beam.inputDirectory}"/sample/1k/vehicles-cav.csv"
                   """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
      .resolve()

    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

    val beamConfig = BeamConfig(config)

    val outputDir: String = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(networkCoordinator.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          val networkHelper: NetworkHelper = new NetworkHelperImpl(networkCoordinator.network)
          install(module(config, scenario, networkCoordinator, networkHelper))
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)

    val controller = services.controler
    controller.run()

    CaccSpec.avgCarModeFromCsv(extractFileName(matsimConfig, beamConfig, outputDir))
  }

  private def extractFileName(
    matsimConfig: Config,
    beamConfig: BeamConfig,
    outputDir: String
  ): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(1, PersonTravelTimeAnalysis.fileBaseName + ".csv")
  }

  "SF Light" must {
    "run 1k scenario car averageTravelTimes(deqsim.cacc.enabled=true) >= averageTravelTimes(deqsim.cacc.enabled=false)" in {
      val avgWithCaccEnabled = runSimulationAndReturnAvgCarTravelTimes(true)
      val avgWithCaccDisabled = runSimulationAndReturnAvgCarTravelTimes(false)

      assert(avgWithCaccEnabled <= avgWithCaccDisabled)
    }

  }
}

object CaccSpec {

  def using[A <: AutoCloseable, B](resource: A)(f: A => B): B = {
    try {
      f(resource)
    } finally {
      resource.close()
    }
  }

  def avgCarModeFromCsv(filePath: String): Double = {
    val carLine = using(Source.fromFile(filePath)) { source =>
      source.getLines().find(_.startsWith("car"))
    }
    val allHourAvg = carLine
      .getOrElse(throw new IllegalStateException("The line does not contain 'car' as TravelTimeMode"))
      .split(",")
      .tail
      .map(_.toDouble)

    val relevantTimes = allHourAvg.filterNot(_ == 0D)
    relevantTimes.sum / relevantTimes.length
  }

}
