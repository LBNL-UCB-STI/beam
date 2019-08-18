package beam.replanning
import beam.agentsim.agents.PersonTestUtil
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.{BeforeMobsimEvent, IterationEndsEvent}
import org.matsim.core.controler.listener.{BeforeMobsimListener, IterationEndsListener}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class NonCarModeIterationPlanCleanerSpec extends FlatSpec with Matchers with BeamHelper {

  "Running Scenario with non car modes clear" must "result only car modes" in {
    val config = ConfigFactory
      .parseString("""
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.agents.vehicles.sharedFleets = []
           |beam.replanning.cleanNonCarModesInIteration = 0
           """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runSimulation(config)
  }

  private def runSimulation(config: Config) = {
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
            case leg: Leg =>
              leg.setMode("walk")
          }
        }
      }
    var nonCarModes = Seq[String]()
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addControlerListenerBinding().toInstance(new BeforeMobsimListener {
            override def notifyBeforeMobsim(event: BeforeMobsimEvent): Unit = {
              nonCarModes = event.getServices.getScenario.getPopulation.getPersons
                .values()
                .asScala
                .flatMap(_.getSelectedPlan.getPlanElements.asScala)
                .collect {
                  case l: Leg if l.getMode.toLowerCase != "car" => l.getMode
                }
                .toSeq
            }
          })
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    services.controler.run()
    assume(nonCarModes.isEmpty, "Something's wildly broken, I am not seeing any trips.")
  }
}
