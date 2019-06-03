package beam.sflight

import scala.io.Source
import beam.analysis.plots.PersonTravelTimeAnalysis
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.tags.{ExcludeRegular, Performance, Periodic}
import beam.utils.{FileUtils, NetworkHelper, NetworkHelperImpl}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.core.config.Config
import org.matsim.core.controler.{AbstractModule, OutputDirectoryHierarchy}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAllConfigMap, ConfigMap, Matchers, WordSpecLike}

class CaccSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {

  private def runSimulationAndReturnAvgCarTravelTimes(caccEnabled: Boolean, iterationNumber: Int): Double = {
    val config = ConfigFactory
      .parseString(s"""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.agentsim.lastIteration = $iterationNumber
                     |beam.physsim.jdeqsim.cacc.enabled = $caccEnabled
                     |beam.physsim.jdeqsim.cacc.minSpeedMetersPerSec = 0
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

    CaccSpec.avgCarModeFromCsv(extractFileName(matsimConfig, beamConfig, outputDir, iterationNumber))
  }

  private def extractFileName(
    matsimConfig: Config,
    beamConfig: BeamConfig,
    outputDir: String,
    iterationNumber: Int
  ): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(iterationNumber, PersonTravelTimeAnalysis.fileBaseName + ".csv")
  }

  "SF Light" must {
    "run 1k scenario car averageTravelTimes(deqsim.cacc.enabled=true) < averageTravelTimes(deqsim.cacc.enabled=false)" taggedAs (Periodic, Performance, ExcludeRegular) in {
      val iteration = 1
      val avgWithCaccEnabled = runSimulationAndReturnAvgCarTravelTimes(caccEnabled = true, iteration)
      val avgWithCaccDisabled = runSimulationAndReturnAvgCarTravelTimes(caccEnabled = false, iteration)

      assert(avgWithCaccEnabled < avgWithCaccDisabled)
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
