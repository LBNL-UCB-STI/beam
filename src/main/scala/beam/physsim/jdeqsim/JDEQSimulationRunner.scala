package beam.physsim.jdeqsim

import beam.analysis.via.EventWriterXML_viaCompatible
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.ProfilingUtils
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.{JDEQSimConfigGroup, JDEQSimulation => MATSimJDEQSimulation}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.population.io.PopulationReader
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

class JDEQSimulationRunner(pathToNetwork: String, pathToPlans: String, config: Config) extends LazyLogging{
  val beamConfig = BeamConfig(config)
  val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()

  val network: Network = NetworkUtils.createNetwork()
  new MatsimNetworkReader(network)
    .readFile(pathToNetwork)

  val jdeqSimScenario = ScenarioUtils.createScenario(matsimConfig).asInstanceOf[MutableScenario]
  jdeqSimScenario.setNetwork(network)

  val jdeqsimEvents = new EventsManagerImpl

  val travelTimeCalculator = new TravelTimeCalculator(network, matsimConfig.travelTimeCalculator)
  jdeqsimEvents.addHandler(travelTimeCalculator)
  jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(true))

  val eventsWriterXML = new EventWriterXML_viaCompatible("physSimEvents.xml.gz", true,1.0)
  jdeqsimEvents.addHandler(eventsWriterXML)

  logger.info(s"Reading plans from '${pathToPlans}'")
  ProfilingUtils.timed("Reading plans", x => logger.info(x)) {
    new PopulationReader(jdeqSimScenario).readFile(pathToPlans)
  }

  def getJDEQSimulation: MATSimJDEQSimulation = {
    val config = new JDEQSimConfigGroup
    config.setFlowCapacityFactor(beamConfig.beam.physsim.flowCapacityFactor)
    config.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
    config.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)
    new MATSimJDEQSimulation(config, jdeqSimScenario, jdeqsimEvents)
  }

  def run(): Unit = {
    val jdeqSimulation = getJDEQSimulation
    logger.info("Running JDEQSimulation")
    ProfilingUtils.timed("Running JDEQSimulation", x =>  logger.info(x)) {
      jdeqSimulation.run()
    }
    logger.info("JDEQSimulation has finished")
  }

}
object JDEQSimulationRunner extends BeamHelper with LazyLogging{
  val pathToNetwork = "C:/temp/travel_time_more_logs/physSimNetwork.xml.gz"
  val pathToPlans = "C:/temp/travel_time_more_logs/10.physsimPlans.xml.gz"

  def main(args: Array[String]): Unit = {
    val (parsedArgs, config: Config) = prepareConfig(args, isConfigArgRequired = true)

    val sim = new JDEQSimulationRunner(pathToNetwork, pathToPlans, config)
    sim.run()
  }
}