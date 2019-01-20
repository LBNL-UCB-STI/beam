package beam.utils
import beam.analysis.DelayMetricAnalysis
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.reflection.ReflectionUtils
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

object EventReplayer extends BeamHelper{
  def main(args: Array[String]): Unit = {
    val path = """C:\temp\0.events.xml"""
    val (_, config) = prepareConfig(args, true)

    val beamConfig = BeamConfig(config)
    val outputDirectory = FileUtils.getConfigOutputFile(
      beamConfig.beam.outputs.baseOutputDirectory,
      beamConfig.beam.agentsim.simulationName,
      beamConfig.beam.outputs.addTimestampToOutputDirectory
    )
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
    LoggingUtil.createFileLogger(outputDirectory)
    matsimConfig.controler.setOutputDirectory(outputDirectory)
    matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.init()
    scenario.setNetwork(networkCoordinator.network)
    val network = networkCoordinator.network
    val transportNetwork = networkCoordinator.transportNetwork

    val networkHelper = new NetworkHelperImpl(network)
    println("Initialized network")

    val s = System.currentTimeMillis()
    val eventsManager = EventsUtils.createEventsManager()
    var numOfEvents: Int = 0
    val arr = new Array[Event](16356210)
    println("Allocated array")
    eventsManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        arr(numOfEvents) = event
        numOfEvents += 1
      }
    })
    val d = new DelayMetricAnalysis(eventsManager, null, null,  networkHelper, beamConfig)
    new MatsimEventsReader(eventsManager).readFile(path)
    val e = System.currentTimeMillis()
    println(s"DelayMetricAnalysis processed in ${e - s} ms. numOfEvents: $numOfEvents")
  }
}
