package beam.agentsim.infrastructure

import beam.agentsim.infrastructure.taz.{H3TAZ, TAZTreeMap}
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{FileUtils, NetworkHelper, NetworkHelperImpl}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class H3TAZSpec extends FlatSpec with Matchers with BeamHelper {

  "this" must "work" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                   """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
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
    scenario.setNetwork(networkCoordinator.network)
    val tazMap = TAZTreeMap.fromShapeFile("test/input/sf-light/shape/sf-light-tazs.shp", "taz")
    val hexMap = H3TAZ.build(scenario, tazMap)
    val popPerHex = H3TAZ.breakdownByPopulation(scenario, 10, hexMap)
    assert(popPerHex.foldLeft(0)(_ + _._3.toInt) == scenario.getPopulation.getPersons.size)
    assert(popPerHex.map(_._2).distinct.size == tazMap.getTAZs.size)
    //H3TAZ.writeToShp("out/test/polygons2.shp", popPerHex)
    //H3TAZ.writeToShp("out/test/polygons.shp", hexMap.getAll.map(hex => (hex, hexMap.getTAZ(hex).toString, 0.0)))
  }

}
