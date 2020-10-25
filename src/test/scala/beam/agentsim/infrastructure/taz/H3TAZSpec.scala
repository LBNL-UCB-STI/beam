package beam.agentsim.infrastructure.taz

import scala.collection.JavaConverters._

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class H3TAZSpec extends FlatSpec with Matchers with BeamHelper {

  "test demand inferred H3 Index set" must "H3 Indexes" in {
    val config = ConfigFactory
      .parseString(
        """
           |beam.actorSystemName = "H3TAZSpec"
           |beam.agentsim.h3taz = {
           |  lowerBoundResolution = 6
           |  upperBoundResolution = 9
           |}
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 0
        """.stripMargin
      )
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
      .map(v => WgsCoordinate(latitude = v.getY, longitude = v.getX))
      .toArray
    val maxDemandPoints = 10
    val lowestResolution = 6
    val highestResolution = 9
    val indexes =
      H3TAZ.getDataPointsInferredH3IndexSet(demandPoints, maxDemandPoints, lowestResolution, highestResolution)
    assert(indexes.nonEmpty, "Set of H3 indexes should not be empty.")
    assert(indexes.map(_._2.length).sum == demandPoints.length, "Not all coordinates were matched to an H3 Index")
    val groups = indexes.groupBy(_._2.length)
    assert(groups.size == 33, "number of different size of clusters should be 33")
    assert(
      (1 to 22).forall(groups.contains) && List(24, 25, 28, 29, 30, 32, 33, 34, 37, 52, 55).forall(groups.contains),
      "something went wrong with the algorithm"
    )
    indexes.foreach {
      case (h3Index, _) =>
        val resolution = h3Index.resolution
        require(
          resolution >= lowestResolution && resolution <= highestResolution,
          s"Expected to have resolution in [$lowestResolution, $highestResolution], but got $resolution"
        )
    }
  }
}
