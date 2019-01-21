package beam.integration
import beam.agentsim.events.ModeChoiceEvent
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.AVAILABLE_MODES
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class CarSharingSpec extends FlatSpec with Matchers with BeamHelper {

  "Running a car-sharing-only scenario with abundant cars" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString("""
        |beam.outputs.events.fileOutputFormats = xml
        |beam.physsim.skipPhysSim = true
        |beam.agentsim.lastIteration = 0
        |beam.agentsim.agents.vehicles.sharedFleets = [
        | {
        |    name = "inexhaustible-reserving"
        |    managerType = "inexhaustible-reserving"
        |    inexhaustible-reserving {
        |      vehicleTypeId = "sharedCar"
        |    }
        | }
        |]
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runCarSharingTest(config)
  }

  "Running a car-sharing-only scenario with one car per person at home" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString("""
        |beam.outputs.events.fileOutputFormats = xml
        |beam.physsim.skipPhysSim = true
        |beam.agentsim.lastIteration = 0
        |beam.agentsim.agents.vehicles.sharedFleets = [
        | {
        |    name = "fixed-non-reserving"
        |    managerType = "fixed-non-reserving"
        |    fixed-non-reserving {
        |      vehicleTypeId = "sharedCar"
        |    }
        | }
        |]
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runCarSharingTest(config)
  }

  private def runCarSharingTest(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSamConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val networkCoordinator = DefaultNetworkCoordinator(beamConfig)
    networkCoordinator.loadNetwork()
    networkCoordinator.convertFrequenciesToTrips()
    scenario.setNetwork(networkCoordinator.network)
    var nonCarTrips = 0
    var trips = 0
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, networkCoordinator))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case modeChoiceEvent: ModeChoiceEvent =>
                  trips = trips + 1
                  if (modeChoiceEvent.getAttributes.get("mode") != "car") {
                    nonCarTrips = nonCarTrips + 1
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
    population.getPersons.keySet.forEach { personId =>
      population.getPersonAttributes.putAttribute(personId.toString, AVAILABLE_MODES, "car")
    }

    // No private vehicles (but we have a car sharing operator)
    val households = scenario.getHouseholds
    households.getHouseholds.values.forEach { household =>
      household.getVehicleIds.clear()
    }
    services.privateVehicles.clear()

    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    controler.run()

    assume(trips != 0, "Something's wildly broken, I am not seeing any trips.")
    assert(nonCarTrips == 0, "Someone wasn't driving even though everybody wants to and cars abound.")
  }

}
