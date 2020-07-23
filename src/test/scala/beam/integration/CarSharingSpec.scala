package beam.integration
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, PersonCostEvent}
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.vehiclesharing.FleetUtils
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class CarSharingSpec extends FlatSpec with Matchers with BeamHelper {

  private val sharedCarTypeId = org.matsim.api.core.v01.Id.create("sharedCar", classOf[BeamVehicleType])

  //clearModes is required for clearing modes defined in population.xml
  "Running a car-sharing-only scenario with abundant cars" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString("""
        |beam.outputs.events.fileOutputFormats = xml
        |beam.physsim.skipPhysSim = true
        |beam.agentsim.lastIteration = 0
        |beam.replanning.clearModes.iteration = 0
        |beam.replanning.clearModes.modes = ["walk", "bike", "car"]
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

  // What happens in this test case is unusual due to Beamville being unusual: A lot of people run towards
  // the same car because they all do exactly the same thing. Only one of them gets it. Repeat.
  // Car sharing was developed against this test case, so it is more or less resilient against this.
  // This test will fail once you add things like maximum number of replanning attempts,
  // or otherwise bailing out of this unusual situation.
  // So please consider making them configurable if you do, if only for the sake of test cases like this one.
  "Running a car-sharing-only scenario with one car per person at home" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString("""
        |beam.outputs.events.fileOutputFormats = xml
        |beam.physsim.skipPhysSim = true
        |beam.agentsim.lastIteration = 0
        |beam.replanning.clearModes.iteration = 0
        |beam.replanning.clearModes.modes = ["walk", "bike", "car"]
        |beam.agentsim.agents.vehicles.sharedFleets = [
        | {
        |    name = "fixed-non-reserving"
        |    managerType = "fixed-non-reserving"
        |    fixed-non-reserving {
        |      vehicleTypeId = "sharedCar",
        |      maxWalkingDistance = 5000
        |    }
        | }
        |]
        |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
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
          install(module(config, beamConfig, scenario, beamScenario))
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

    DefaultPopulationAdjustment(services).update(scenario)
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

  // REPOSITION
  "Reposition scenario" must "results at least a person driving in the second iteration" in {
    val config = ConfigFactory
      .parseString("""
         |beam.outputs.events.fileOutputFormats = xml
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 1
         |beam.outputs.writeSkimsInterval = 1
         |beam.agentsim.agents.vehicles.sharedFleets = [
         | {
         |    name = "fixed-non-reserving-fleet-by-taz"
         |    managerType = "fixed-non-reserving-fleet-by-taz"
         |    fixed-non-reserving-fleet-by-taz {
         |      vehicleTypeId = "sharedCar"
         |      maxWalkingDistance = 1000
         |      fleetSize = 40
         |      vehiclesSharePerTAZFromCSV = "output/test/vehiclesSharePerTAZ.csv"
         |    }
         |    reposition {
         |      name = "min-availability-undersupply-algorithm"
         |      repositionTimeBin = 3600
         |      statTimeBin = 300
         |      min-availability-undersupply-algorithm {
         |        matchLimit = 99999
         |      }
         |   }
         | }
         |]
         |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
      """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runRepositionTest(config)
  }

  private def runRepositionTest(config: Config): Unit = {
    import beam.agentsim.infrastructure.taz.TAZ
    import org.matsim.api.core.v01.{Coord, Id}

    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    var iteration = -1
    var carsharingTripsIt0 = 0
    var carsharingTripsIt1 = 0
    FleetUtils.writeCSV(
      "output/test/vehiclesSharePerTAZ.csv",
      Vector(
        (Id.create("1", classOf[TAZ]), new Coord(0, 0), 0.0),
        (Id.create("2", classOf[TAZ]), new Coord(0, 0), 1.0),
        (Id.create("3", classOf[TAZ]), new Coord(0, 0), 0.0),
        (Id.create("4", classOf[TAZ]), new Coord(0, 0), 0.0)
      )
    )
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ModeChoiceEvent =>
                  if (e.getAttributes.get("mode") == "car") {
                    if (iteration == 0) {
                      carsharingTripsIt0 += 1
                    } else {
                      carsharingTripsIt1 += 1
                    }
                  }
                case _ =>
              }
            }
          })
          addControlerListenerBinding().toInstance(new IterationStartsListener {
            override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
              iteration = event.getIteration
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

    DefaultPopulationAdjustment(services).update(scenario)
    services.controler.run()

    assume(carsharingTripsIt0 == 0, "no agent is supposed to be driving in iteration 1")
    assume(carsharingTripsIt1 > 0, "at least one agent has to be driving in iteration 2")
  }

}
