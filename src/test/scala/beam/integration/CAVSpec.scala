package beam.integration

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.ModeChoiceEvent
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class CAVSpec extends FlatSpec with Matchers with BeamHelper {

  private val sharedCarTypeId = Id.create("sharedCar", classOf[BeamVehicleType])

  "Running a CAV-only scenario with a couple of CAVs" must "result in everybody using CAV or walk" in {
    val config = ConfigFactory
      .parseString(
        """
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
