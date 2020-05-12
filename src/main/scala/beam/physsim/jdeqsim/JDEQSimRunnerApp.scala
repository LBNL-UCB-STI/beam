package beam.physsim.jdeqsim

import beam.sim.{BeamConfigChangesObservable, BeamHelper}
import beam.utils.{ProfilingUtils, Statistics}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.config.{Config => MatsimConfig}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

object JDEQSimRunnerApp extends StrictLogging {

  // Run it using gradle: `./gradlew :execute -PmaxRAM=20 -PmainClass=beam.physsim.jdeqsim.JDEQSimRunnerApp -PappArgs=["'--config', 'test/input/sf-light/sf-light-1k.conf'"] -PlogbackCfg=logback.xml
  def main(args: Array[String]): Unit = {
    val beamHelper = new BeamHelper {}
    val (_, config) = beamHelper.prepareConfig(args, true)
    val execCfg = beamHelper.setupBeamWithConfig(config)

    val networkFile = "d:/Work/beam/GPU/network-output.xml"
    val populationFile = "d:/Work/beam/GPU/population-output.xml"
    val pathToOutput = "d:/Work/beam/GPU/result"

    val network = ProfilingUtils.timed(s"Read network from $networkFile", x => logger.info(x)) {
      readNetwork(networkFile)
    }
    logger.info(s"Read network with ${network.getNodes.size()} nodes and ${network.getLinks.size()} links")

    val scenario = ProfilingUtils.timed(s"Read population and plans from ${populationFile}", x => logger.info(x)) {
      readPopulation(execCfg.matsimConfig, populationFile)
    }
    val maxEndTime = scenario.getPopulation.getPersons
      .values()
      .asScala
      .map { p =>
        val endTimes = p.getPlans.asScala.flatMap { plan =>
          plan.getPlanElements.asScala.collect { case act: Activity => act.getEndTime }
        }
        if (endTimes.isEmpty) 0 else endTimes.max
      }
      .max

    val totalPlans = scenario.getPopulation.getPersons.values().asScala.map(_.getPlans.size()).sum
    logger.info(s"Read scenario with ${scenario.getPopulation.getPersons.size()} people and $totalPlans plans")
    logger.info(s"Max end time for the activity is ${maxEndTime} seconds = ${maxEndTime / 3600} hours")
    scenario.setNetwork(network)

    val times = ArrayBuffer[Double]()

    (1 to 50).map { iter =>
      val s = System.currentTimeMillis()
      val outputDirectoryHierarchy =
        new OutputDirectoryHierarchy(pathToOutput, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
      outputDirectoryHierarchy.createIterationDirectory(0)

      val physSim = new JDEQSimRunner(
        execCfg.beamConfig,
        scenario,
        scenario.getPopulation,
        outputDirectoryHierarchy,
        new java.util.HashMap[String, java.lang.Boolean](),
        new BeamConfigChangesObservable(execCfg.beamConfig),
        agentSimIterationNumber = 0
      )
      physSim.simulate(currentPhysSimIter = 0, writeEvents = false)
      val e = System.currentTimeMillis()
      val diff = e - s
      logger.info(s"Iter $iter took ${diff / 1000} seconds")
      times += diff
    }
    logger.info(s"Statistics: ${Statistics(times)}")
  }

  private def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(path)
    network.getLinks.values().asScala.foreach { link =>
        link.setCapacity(10000)
    }
    network
  }

  private def readPopulation(matsimConfig: MatsimConfig, path: String): MutableScenario = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    new PopulationReader(scenario).readFile(path)
    scenario
  }
}
