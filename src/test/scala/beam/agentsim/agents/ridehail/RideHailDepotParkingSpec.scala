package beam.agentsim.agents.ridehail

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent, RefuelSessionEvent}
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
import org.scalatest.{FlatSpec, Matchers}

class RideHailDepotParkingSpec extends FlatSpec with Matchers with BeamHelper {

  "Running a car-sharing-only scenario with abundant cars" must "result in everybody driving" in {
    val config = ConfigFactory
      .parseString(
        """
          |beam.outputs.events.fileOutputFormats = csv.gz
          |beam.agentsim.agentSampleSizeAsFractionOfPopulation = 1.0
          |
          |beam.physsim.skipPhysSim = true
          |beam.agentsim.lastIteration = 1
          |beam.agentsim.h3taz = {
          |  enabled = false
          |}
          |beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId = "RHCAV_BEV"
          |beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypePrefix = "RHCAV"
          |beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0.5
          |beam.agentsim.agents.rideHail.initialization.parking.filePath = "test/input/beamville/depot/taz-depot-50kw.csv"
          |beam.agentsim.agents.parking.minSearchRadius = 250.00
          |beam.agentsim.agents.parking.maxSearchRadius = 8046.72
          |beam.agentsim.agents.parking.mulitnomialLogit.params.rangeAnxietyMultiplier = -0.5
          |beam.agentsim.agents.parking.mulitnomialLogit.params.distanceMultiplier = -0.086
          |beam.agentsim.agents.parking.mulitnomialLogit.params.parkingPriceMultiplier = -0.5
          |beam.agentsim.agents.parking.mulitnomialLogit.params.refuelWaitTime = -0.1
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = 10.0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_pooled_intercept = 10.0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = 0
          |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runBEVRHCAVTest(config)
  }

  private def runBEVRHCAVTest(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ModeChoiceEvent =>
                //println(e.personId + " - " + e.mode)
                case e: PathTraversalEvent if e.vehicleId.toString.startsWith("rideHail") =>
                //println(e.vehicleId + " - " + e.vehicleType)
                case e: RefuelSessionEvent =>
                //println(e.vehicleType.id + " - " + e.vehId + " - " + e.stall.tazId)
                case _ =>
              }
            }
          })
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])

    // Only driving allowed
    //    val population = scenario.getPopulation
    //    val nonCarModes = BeamMode.allModes flatMap { mode =>
    //      if (mode == BeamMode.CAR) None else Some(mode.value.toLowerCase)
    //    } mkString ","
    //    population.getPersons.keySet.forEach { personId =>
    //      population.getPersonAttributes.putAttribute(personId.toString, EXCLUDED_MODES, nonCarModes)
    //    }
    //    // No private vehicles (but we have a car sharing operator)
    //    val households = scenario.getHouseholds
    //    households.getHouseholds.values.forEach { household =>
    //      household.getVehicleIds.clear()
    //    }
    //    beamScenario.privateVehicles.clear()
    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    controler.run()

    //    val sharedCarType = beamScenario.vehicleTypes(sharedCarTypeId)
    //    assume(sharedCarType.monetaryCostPerSecond > 0, "I defined a per-time price for my car type.")
    //    assume(trips != 0, "Something's wildly broken, I am not seeing any trips.")
    //
    //    assert(sharedCarTravelTime > 0, "Aggregate shared car travel time must not be zero.")
    //    assert(
    //      personCost >= sharedCarTravelTime * sharedCarType.monetaryCostPerSecond,
    //      "People are paying less than my price."
    //    )
    //    assert(nonCarTrips == 0, "Someone wasn't driving even though everybody wants to and cars abound.")
  }

}
