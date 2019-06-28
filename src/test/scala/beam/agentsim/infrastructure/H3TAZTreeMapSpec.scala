package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.taz.H3TAZTreeMap
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{FileUtils, H3Utils, NetworkHelper, NetworkHelperImpl}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class H3TAZTreeMapSpec extends FlatSpec with Matchers with BeamHelper {

  "this" must "work" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                   """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    testH3(config)
  }


  private def testH3(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()
    val networkHelper: NetworkHelper = new NetworkHelperImpl(networkCoordinator.network)
    scenario.setNetwork(networkCoordinator.network)

    val tazMap = TAZTreeMap.fromShapeFile(beamConfig.beam.agentsim.taz.filePath, "taz")
    val hexMap = H3TAZTreeMap.build(scenario, tazMap)

    H3Utils.writeHexToShp(H3TAZTreeMap.toPolygons(hexMap), "out/test/polygons.shp")
  }

}

