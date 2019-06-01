package beam.integration
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, PersonCostEvent}
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

class CarSharingSpec extends FlatSpec with Matchers with BeamHelper {

  private val sharedCarTypeId = Id.create("sharedCar", classOf[BeamVehicleType])

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

  "Running a car-sharing-only scenario with one car per person at home" must "result in everybody driving" ignore {
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
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    var nonCarTrips = 0
    var trips = 0
    var sharedCarTravelTime = 0
    var personCost = 0d
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ModeChoiceEvent =>
                  trips = trips + 1
                  if (e.getAttributes.get("mode") != "car") {
                    nonCarTrips = nonCarTrips + 1
                  }
                case e: PathTraversalEvent if e.vehicleType == sharedCarTypeId.toString =>
                  sharedCarTravelTime = sharedCarTravelTime + (e.arrivalTime - e.departureTime)
                case e: PersonCostEvent =>
                  personCost = personCost + e.getNetCost
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
      if (mode == BeamMode.CAR) None else Some(mode.value.toLowerCase)
    } mkString ","
    population.getPersons.keySet.forEach { personId =>
      population.getPersonAttributes.putAttribute(personId.toString, EXCLUDED_MODES, nonCarModes)
    }

    // No private vehicles (but we have a car sharing operator)
    val households = scenario.getHouseholds
    households.getHouseholds.values.forEach { household =>
      household.getVehicleIds.clear()
    }
    beamScenario.privateVehicles.clear()

    DefaultPopulationAdjustment(services, beamScenario).update(scenario)
    val controler = services.controler
    controler.run()

    val sharedCarType = beamScenario.vehicleTypes(sharedCarTypeId)
    assume(sharedCarType.monetaryCostPerSecond > 0, "I defined a per-time price for my car type.")
    assume(trips != 0, "Something's wildly broken, I am not seeing any trips.")

    assert(sharedCarTravelTime > 0, "Aggregate shared car travel time must not be zero.")
    assert(
      personCost >= sharedCarTravelTime * sharedCarType.monetaryCostPerSecond,
      "People are paying less than my price."
    )
    assert(nonCarTrips == 0, "Someone wasn't driving even though everybody wants to and cars abound.")
  }

}
