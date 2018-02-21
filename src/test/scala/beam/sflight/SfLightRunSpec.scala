package beam.sflight

import beam.agentsim.events.ModeChoiceEvent
import beam.router.r5.NetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.{BeamConfigUtils, FileUtils}
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

/**
  * Created by colinsheppard
  */

class SfLightRunSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  "SF Light" must {
    "run without error and at least one person chooses car mode" in {
      val config = BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/sf-light/sf-light.conf").resolve()
        .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSamConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig = BeamConfig(config)

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
      val networkCoordinator = new NetworkCoordinator(beamConfig, scenario.getTransitVehicles)
      networkCoordinator.loadNetwork()
      scenario.setNetwork(networkCoordinator.network)
      var nCarTrips = 0
      val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, networkCoordinator.transportNetwork))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case modeChoiceEvent: ModeChoiceEvent =>
                  if (modeChoiceEvent.getAttributes.get("mode").equals("car")) {
                    nCarTrips = nCarTrips + 1
                  }
                case _ =>
              }
            }
          })
        }
      })
      val controler = injector.getInstance(classOf[BeamServices]).controler
      controler.run()
      assert(nCarTrips > 1)
    }
  }

}
