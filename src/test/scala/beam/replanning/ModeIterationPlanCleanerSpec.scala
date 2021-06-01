package beam.replanning
import beam.agentsim.agents.PersonTestUtil
import beam.router.Modes.BeamMode
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

import scala.collection.JavaConverters._
import scala.util.Random

class ModeIterationPlanCleanerSpec extends AnyFlatSpec with Matchers with BeamHelper {

  private val random = Random.self

  "Running Scenario with non car modes clear" must "result only car modes" in {
    val config = ConfigFactory
      .parseString("""
           |beam.actorSystemName = "ModeIterationPlanCleanerSpec"
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.agents.vehicles.sharedFleets = []
           |beam.replanning.clearModes.modes = ["walk", "bike"]
           |beam.replanning.clearModes.iteration = 1
           |beam.replanning.clearModes.strategy = "AtBeginningOfIteration"
           """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    scenario.getPopulation.getPersons.values.asScala
      .foreach(p => PersonTestUtil.putDefaultBeamAttributes(p, BeamMode.allModes))
    scenario.getPopulation.getPersons
      .values()
      .forEach { person =>
        {
          person.getSelectedPlan.getPlanElements.asScala.collect {
            case leg: Leg => setRandomMode(leg)
          }
        }
      }
    val planCleaner = new ModeIterationPlanCleaner(beamConfig, scenario)
    planCleaner.clearModesAccordingToStrategy(0)
    getNonCarLegs(scenario) should not be empty
    planCleaner.clearModesAccordingToStrategy(2)
    getNonCarLegs(scenario) should not be empty
    planCleaner.clearModesAccordingToStrategy(1)
    getNonCarLegs(scenario) shouldBe empty
  }

  private def getNonCarLegs(scenario: MutableScenario) = {
    scenario.getPopulation.getPersons
      .values()
      .asScala
      .flatMap(_.getSelectedPlan.getPlanElements.asScala)
      .collect {
        case l: Leg if l.getMode.nonEmpty && l.getMode.toLowerCase != "car" => l
      }
  }

  private def setRandomMode(leg: Leg): Unit = {
    val modesArray = Array[String]("car", "walk", "bike")
    leg.setMode(modesArray(random.nextInt(3)))
  }
}
