package beam.integration

import beam.agentsim.events.ModeChoiceEvent
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CAVSpec extends AnyFlatSpec with Matchers with BeamHelper {

  // CI shows this test is not working with this exception:
  //
  //  17:39:39 CAVSpec:
  //  17:39:39 Running a CAV-only scenario with a couple of CAVs
  //  17:39:39 - must result in everybody using CAV or walk *** FAILED *** (21 seconds, 849 milliseconds)
  //  17:39:39   java.lang.NullPointerException:
  //  17:39:39   at beam.sim.BeamSim.$anonfun$notifyIterationEnds$16(BeamSim.scala:492)
  //  17:39:39   at scala.runtime.java8.JFunction0$mcV$sp.apply(JFunction0$mcV$sp.java:23)
  //  17:39:39   at scala.concurrent.Future$.$anonfun$apply$1(Future.scala:659)
  //  17:39:39   at scala.util.Success.$anonfun$map$1(Try.scala:255)
  //  17:39:39   at scala.util.Success.map(Try.scala:213)
  //  17:39:39   at scala.concurrent.Future.$anonfun$map$1(Future.scala:292)
  //  17:39:39   at scala.concurrent.impl.Promise.liftedTree1$1(Promise.scala:33)
  //  17:39:39   at scala.concurrent.impl.Promise.$anonfun$transform$1(Promise.scala:33)
  //  17:39:39   at scala.concurrent.impl.CallbackRunnable.run(Promise.scala:64)
  //  17:39:39   at java.util.concurrent.ForkJoinTask$RunnableExecuteAction.exec(ForkJoinTask.java:1402)

  "Running a CAV-only scenario with a couple of CAVs" must "result in everybody using CAV or walk" in {
    val config = ConfigFactory
      .parseString(
        """
           |beam.agentsim.simulationName = "beamville_for_CAVSpec"
           |beam.actorSystemName = "CAVSpec"
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.agents.vehicles.sharedFleets = []
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runCAVTest(config)
  }

  private def runCAVTest(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    var cavTrips = 0
    var trips = 0
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ModeChoiceEvent =>
                  trips = trips + 1
                  if (e.getAttributes.get("mode") == "cav") {
                    cavTrips = cavTrips + 1
                  }
                case _ =>
              }
            }
          })
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])

    // Only driving allowed
    val population = scenario.getPopulation
    val nonCarModes = BeamMode.allModes flatMap { mode =>
      if (mode == BeamMode.CAV) None else Some(mode.value.toLowerCase)
    } mkString ","
    population.getPersons.keySet.forEach { personId =>
      population.getPersonAttributes.putAttribute(personId.toString, EXCLUDED_MODES, nonCarModes)
    }

    var cavVehicles = 0
    val households = scenario.getHouseholds
    households.getHouseholds.values.forEach { household =>
      household.getVehicleIds.removeIf { id =>
        val veh = beamScenario.privateVehicles(id)
        veh.beamVehicleType.automationLevel < 3
      }
      cavVehicles = cavVehicles + household.getVehicleIds.size()
    }

    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    controler.run()

    assume(trips != 0, "Something's wildly broken, I am not seeing any trips.")
    assume(cavVehicles != 0, "Nobody has a CAV vehicle in test scenario, nothing to test.")
    assert(cavTrips >= cavVehicles, "Not enough CAV trips (by mode choice) seen.")
  }

}
