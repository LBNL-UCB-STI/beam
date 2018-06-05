package beam.agentsim.agents.rideHail

import java.nio.file.Paths

import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.agentsim.agents.rideHail.TNCIterationsStatsCollector
import beam.utils.BeamConfigUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.scalatest.{BeforeAndAfterAll, ConfigMap, Matchers, WordSpecLike}

class TNCIterationsStatsCollectorSpec extends WordSpecLike with Matchers with BeforeAndAfterAll {

  private val LAST_ITER_CONF_PATH = "matsim.modules.controler.lastIteration"

  private var baseConf: Config = _
  private var totalIterations: Int = _

  override def beforeAll: Unit = {
    val confPath = "test/input/sf-light/sf-light.conf"
    totalIterations = 1

    baseConf = testConfig(confPath).withValue(LAST_ITER_CONF_PATH, ConfigValueFactory.fromAnyRef(totalIterations-1))
    baseConf.getInt(LAST_ITER_CONF_PATH) should be (totalIterations-1)
  }
  "A TNC Iterations Stats Collector " must {
    "collect " in {
      val events = EventsUtils.createEventsManager
      val tncHandler = new TNCIterationsStatsCollector(events, BeamConfig(baseConf))


      val reader = new MatsimEventsReader(events)
      reader.readFile("test/input/beamville/test-data/beamville.events.xml")

      tncHandler.rideHailStats

    }
  }
}
