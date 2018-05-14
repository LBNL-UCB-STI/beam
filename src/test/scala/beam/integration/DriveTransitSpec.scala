package beam.integration

import beam.agentsim.events.ModeChoiceEvent
import beam.router.r5.NetworkCoordinator
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.FileUtils
import com.typesafe.config.ConfigValueFactory
import org.scalatest.{Matchers, WordSpecLike}
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

import scala.concurrent.duration._

/**
  * Created by fdariasm on 29/08/2017
  *
  */

class DriveTransitSpec extends WordSpecLike with Matchers with BeamHelper {

  "DriveTransit trips" must {
    "all run to completion" in {
      val config = testConfig("test/input/sf-light/sf-light-1k.conf")
        .withValue("beam.agentsim.agents.modalBehaviors.modeChoiceClass", ConfigValueFactory.fromAnyRef("ModeChoiceMultinomialLogit"))
        .withValue("beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept", ConfigValueFactory.fromAnyRef(9999))
        .withValue("matsim.modules.controler.lastIteration", ConfigValueFactory.fromAnyRef(0))
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
      var nDepartures = 0
      var nArrivals = 0
      val injector = org.matsim.core.controler.Injector.createInjector(scenario.getConfig, new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, networkCoordinator.transportNetwork))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case depEvent: PersonDepartureEvent =>
                  nDepartures = nDepartures + 1
                case arrEvent: PersonArrivalEvent =>
                  nArrivals = nArrivals + 1
                case _ =>
              }
            }
          })
        }
      })
      val controler = injector.getInstance(classOf[BeamServices]).controler
      controler.run()
      assert(nDepartures == nArrivals)
    }
  }

}
