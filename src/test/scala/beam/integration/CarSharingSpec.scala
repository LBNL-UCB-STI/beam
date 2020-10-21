/*package beam.integration
import beam.agentsim.agents.PersonTestUtil.putDefaultBeamAttributes
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, PersonCostEvent}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.vehiclesharing.FleetUtils
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.data.synthpop.models.Models.Household
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.HouseholdsFactoryImpl
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.{mutable, JavaConverters}
import scala.collection.JavaConverters._
import scala.util.Random

class CarSharingSpec extends FlatSpec with Matchers with BeamHelper {

  private val sharedCarTypeId = org.matsim.api.core.v01.Id.create("sharedCar", classOf[BeamVehicleType])

  val beamVilleTaz = Map(
    "1" -> (Id.create("1", classOf[TAZ]), new Coord(167141.3, 1112.351)),
    "2" -> (Id.create("2", classOf[TAZ]), new Coord(167141.3, 3326.017)),
    "3" -> (Id.create("3", classOf[TAZ]), new Coord(169369.8, 1112.351)),
    "4" -> (Id.create("4", classOf[TAZ]), new Coord(169369.8, 3326.017))
  )

  //clearModes is required for clearing modes defined in population.xml
  "Running a car-sharing-only scenario with abundant cars" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString("""
        |beam.actorSystemName = "CarSharingSpec"
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
        |beam.actorSystemName = "CarSharingSpec"
        |beam.outputs.events.fileOutputFormats = xml
        |beam.physsim.skipPhysSim = true
        |beam.agentsim.lastIteration = 0
        |beam.outputs.writeGraphs = false
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

  // REPOSITION IS NOT WORKING TODO IT NEEDS TO BE FIXED
//  "Reposition scenario" must "results at least a person driving in the second iteration" in {
//    val config = ConfigFactory
//      .parseString("""
//         |beam.outputs.events.fileOutputFormats = xml
//         |beam.physsim.skipPhysSim = true
//         |beam.agentsim.lastIteration = 1
//         |beam.outputs.writeSkimsInterval = 1
//         |beam.agentsim.agents.vehicles.sharedFleets = [
//         | {
//         |    name = "fixed-non-reserving-fleet-by-taz"
//         |    managerType = "fixed-non-reserving-fleet-by-taz"
//         |    fixed-non-reserving-fleet-by-taz {
//         |      vehicleTypeId = "sharedCar"
//         |      maxWalkingDistance = 1000
//         |      fleetSize = 40
//         |      vehiclesSharePerTAZFromCSV = "output/test/vehiclesSharePerTAZ.csv"
//         |    }
//         |    reposition {
//         |      name = "min-availability-undersupply-algorithm"
//         |      repositionTimeBin = 3600
//         |      statTimeBin = 300
//         |      min-availability-undersupply-algorithm {
//         |        matchLimit = 99999
//         |      }
//         |   }
//         | }
//         |]
//         |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
//      """.stripMargin)
//      .withFallback(testConfig("test/input/beamville/beam.conf"))
//      .resolve()
//    runRepositionTest(config)
//  }
//
//  private def runRepositionTest(config: Config): Unit = {
//    val configBuilder = new MatSimBeamConfigBuilder(config)
//    val matsimConfig = configBuilder.buildMatSimConf()
//    val beamConfig = BeamConfig(config)
//    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
//    val beamScenario = loadScenario(beamConfig)
//    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
//    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
//    scenario.setNetwork(beamScenario.network)
//
//    var iteration = -1
//    var carsharingTripsIt0 = 0
//    var carsharingTripsIt1 = 0
//
//    val injector = org.matsim.core.controler.Injector.createInjector(
//      scenario.getConfig,
//      new AbstractModule() {
//        override def install(): Unit = {
//          install(module(config, beamConfig, scenario, beamScenario))
//          addEventHandlerBinding().toInstance(new BasicEventHandler {
//            override def handleEvent(event: Event): Unit = {
//              event match {
//                case e: ModeChoiceEvent =>
//                  println(e.personId.toString + " " + e.getAttributes.get("mode"))
//                  if (e.getAttributes.get("mode") == "car") {
//                    if (iteration == 0) {
//                      carsharingTripsIt0 += 1
//                    } else {
//                      carsharingTripsIt1 += 1
//                    }
//                  }
//                case _ =>
//              }
//            }
//          })
//          addControlerListenerBinding().toInstance(new IterationStartsListener {
//            override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
//              iteration = event.getIteration
//            }
//          })
//        }
//      }
//    )
//    val services = injector.getInstance(classOf[BeamServices])
//
//    // 3 person in a single household in the town
//    val population = scenario.getPopulation
//    (1 to 50).map(Id.create(_, classOf[Person])).foreach(population.removePerson)
//
//    // No private vehicles (but we have a car sharing operator)
//    val households = scenario.getHouseholds
//    households.getHouseholds.values.forEach { household =>
//      household.getVehicleIds.clear()
//    }
//    beamScenario.privateVehicles.clear()
//    (2 to 21).map(Id.create(_, classOf[Household])).foreach(households.getHouseholds.remove)
//
//    population.getPersons.size() should be (0)
//    scenario.getHouseholds.getHouseholds.size() should be (1)
//
//    val nonCarModes = BeamMode.allModes flatMap { mode =>
//      if (mode == BeamMode.CAR) None else Some(mode.value.toLowerCase)
//    } mkString ","
//    (1 to 50).foreach {
//      i =>
//        val person = createTestPerson(s"dummyAgent-$i")
//        population.addPerson(person)
//        population.getPersonAttributes.putAttribute(person.getId.toString, EXCLUDED_MODES, nonCarModes)
//    }
//
//    val household = households.getHouseholds.get(Id.create(1, classOf[Household]))
//    household.asInstanceOf[org.matsim.households.HouseholdImpl].setMemberIds(JavaConverters.seqAsJavaList(population.getPersons.keySet().asScala.toList))
//
//    DefaultPopulationAdjustment(services).update(scenario)
//
//    FleetUtils.writeCSV(
//      "output/test/vehiclesSharePerTAZ.csv",
//      Vector(
//        beamVilleTaz.get("1").map(x => (x._1, x._2, 0.0)).get,
//        beamVilleTaz.get("2").map(x => (x._1, x._2, 1.0)).get,
//        beamVilleTaz.get("3").map(x => (x._1, x._2, 0.0)).get,
//        beamVilleTaz.get("4").map(x => (x._1, x._2, 0.0)).get
//      )
//    )
//    services.controler.run()
//
//    assume(carsharingTripsIt0 == 0, "no agent is supposed to be driving in iteration 1")
//    assume(carsharingTripsIt1 > 0, "at least one agent has to be driving in iteration 2")
//  }
//
//  private def createTestPerson(personIdStr: String) = {
//    val personId = Id.createPersonId(personIdStr)
//    val homeLocation = beamVilleTaz("4")._2
//    val workLocation = beamVilleTaz("1")._2
//    val person = PopulationUtils.getFactory.createPerson(personId)
//    putDefaultBeamAttributes(person, Vector(BeamMode.CAR))
//    val plan = PopulationUtils.getFactory.createPlan()
//    val homeActivity = PopulationUtils.createActivityFromLinkId("home", Id.createLinkId(1))
//    homeActivity.setEndTime(28800) // 8:00:00 AM
//    homeActivity.setCoord(homeLocation)
//    plan.addActivity(homeActivity)
//    val leg = PopulationUtils.createLeg("")
//    plan.addLeg(leg)
//    val workActivity = PopulationUtils.createActivityFromLinkId("work", Id.createLinkId(2))
//    workActivity.setEndTime(61200) //5:00:00 PM
//    workActivity.setCoord(workLocation)
//    plan.addActivity(workActivity)
//    person.addPlan(plan)
//    person
//  }

}
*/
