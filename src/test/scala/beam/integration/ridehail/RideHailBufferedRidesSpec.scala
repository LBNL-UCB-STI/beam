package beam.integration.ridehail

import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.mockito.MockitoSugar

class RideHailBufferedRidesSpec extends FlatSpec with BeamHelper with MockitoSugar {
// TODO: include events handling as with : RideHailPassengersEventsSpec
  it should "be able to run for 1 iteration without exceptions" in {
    val config = testConfig("test/input/beamville/beam.conf")
      .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml,csv"))
      .withValue(
        "beam.agentsim.agents.rideHail.allocationManager.name",
        ConfigValueFactory.fromAnyRef(
          "Test_beam.integration.ridehail.allocation.DummyRideHailDispatchWithBufferingRequests"
          //"DEFAULT_MANAGER"
        )
      )
      .resolve()
    runBeamWithConfig(config)
  }

}
