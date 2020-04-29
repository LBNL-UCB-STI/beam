package beam.agentsim.infrastructure.taz

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, PersonCostEvent}
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

class H3TAZSpec extends FlatSpec with Matchers with BeamHelper {

  "test demand inferred H3 Index set" must "H3 Indexes" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
        """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
      .resolve()
    runGetDemandInferredH3IndexSet(config)
  }

  def runGetDemandInferredH3IndexSet(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    val demandPoints = scenario.getPopulation.getPersons.asScala
      .flatMap(_._2.getPlans.get(0).getPlanElements.asScala.filter(_.isInstanceOf[Activity]))
      .map(_.asInstanceOf[Activity].getCoord)
      .toArray
    val indexes = services.beamScenario.h3taz.getDemandInferredH3IndexSet(demandPoints, 10, 6, 10)
    assert(indexes.nonEmpty, "Set of H3 indexes should not be empty.")
    assert(indexes.flatMap(_._2).length == demandPoints.length, "Not all coordinates were matched to an H3 Index")
  }

}
