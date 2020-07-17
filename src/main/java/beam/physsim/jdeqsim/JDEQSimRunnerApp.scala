package beam.physsim.jdeqsim

import java.lang

import beam.sim.{BeamConfigChangesObservable, BeamHelper}
import beam.utils.ProfilingUtils
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
import scala.util.Random

object JDEQSimRunnerApp extends StrictLogging {

  // Run it using gradle: `./gradlew :execute -PmaxRAM=20 -PmainClass=beam.physsim.jdeqsim.JDEQSimRunnerApp -PappArgs=["'--config', 'test/input/sf-light/sf-light-1k.conf'"] -PlogbackCfg=logback.xml
  def main(args: Array[String]): Unit = {
    val beamHelper = new BeamHelper {}
    val (_, config) = beamHelper.prepareConfig(args, isConfigArgRequired = true)
    val execCfg = beamHelper.setupBeamWithConfig(config)

    val networkFile = "output/sf-light/sf-light-25k/outputNetwork.xml.gz"
    val populationFile = "output/sf-light/sf-light-25k/ITERS/it.0/0.experienced_plans.xml.gz"
    val pathToOutput = s"${execCfg.outputDirectory}/phys-sym"

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

    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(pathToOutput, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)
    outputDirectoryHierarchy.createIterationDirectory(0)

    val rnd = Random
    val caccMap: Map[String, lang.Boolean] = if (execCfg.beamConfig.beam.physsim.jdeqsim.cacc.enabled) {
      scenario.getPopulation.getPersons
        .values()
        .asScala
        .map { person =>
          person.getId.toString -> Boolean.box(rnd.nextBoolean())
        }
        .toMap
    } else {
      Map.empty
    }

    val physSim = new JDEQSimRunner(
      execCfg.beamConfig,
      scenario,
      scenario.getPopulation,
      outputDirectoryHierarchy,
      caccMap.asJava,
      new BeamConfigChangesObservable(execCfg.beamConfig),
      agentSimIterationNumber = 0
    )
    physSim.simulate(currentPhysSimIter = 0, writeEvents = true)
    logger.info(s"Results are in ${execCfg.outputDirectory}")
  }

  private def readNetwork(path: String): Network = {
    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(path)
    network
  }

  private def readPopulation(matsimConfig: MatsimConfig, path: String): MutableScenario = {
    val scenario = ScenarioUtils.createMutableScenario(matsimConfig)
    new PopulationReader(scenario).readFile(path)
    scenario
  }
}
