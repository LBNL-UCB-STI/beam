package beam.integration

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.events.{ChargingPlugInEvent, ChargingPlugOutEvent, PathTraversalEvent, RefuelSessionEvent}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Leg
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnrouteChargingSpec extends AnyFlatSpec with Matchers with BeamHelper {
  private val bevCarId = Id.create("BEV", classOf[BeamVehicleType])
  private val vehicleId = Id.create("390-1", classOf[Vehicle])
  private val filesPath = s"${System.getenv("PWD")}/test/test-resources/sf-light-1p/input"

  val config: Config = ConfigFactory
    .parseString(
      s"""|beam.outputs.events.fileOutputFormats = csv
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 0
         |beam.agentsim.tuning.transitCapacity = 0.0
         |beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0
         |beam.agentsim.agents.vehicles.sharedFleets = []
         |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles.csv.gz"
         |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes.csv"
         |beam.agentsim.taz.parkingFilePath = $filesPath"/taz-parking.csv.gz"
         |beam.agentsim.agents.plans.inputPlansFilePath = $filesPath"/population.xml.gz"
         |beam.agentsim.agents.households.inputFilePath = $filesPath"/households.xml.gz"
         |beam.agentsim.agents.vehicles.meanPrivateVehicleStartingSOC = 0.5
      """.stripMargin
    )
    .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
    .resolve()

  "Running a single person car-only scenario" must "catch en-routing event" in {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    var beenToEnroute: Boolean = false

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case _ => // TODO catch important events for enroute
              }
            }
          })
        }
      }
    )

    val beamVilleCarEVType = beamScenario.vehicleTypes(bevCarId)
    beamVilleCarEVType.primaryFuelType shouldBe Electricity

    val services = injector.getInstance(classOf[BeamServices])
    val transportNetwork = injector.getInstance(classOf[TransportNetwork])

    val population = scenario.getPopulation
    val households = scenario.getHouseholds

    assume(population.getPersons.size == 1, "A single person in the household")
    assume(households.getHouseholds.size == 1, "A single household in the town")

    // Only driving allowed
    val noCarModes = BeamMode.allModes.filter(_ != BeamMode.CAR).map(_.value.toLowerCase) mkString ","
    population.getPersons.forEach { case (personId, person) =>
      person.getPlans.forEach { plan =>
        plan.getPlanElements.forEach {
          case leg: Leg => leg.setMode("car")
          case _        =>
        }
      }
      population.getPersonAttributes.putAttribute(personId.toString, EXCLUDED_MODES, noCarModes)
    }
    transportNetwork.transitLayer.tripPatterns.clear()
    DefaultPopulationAdjustment(services).update(scenario)
    val controler = services.controler
    controler.run()

    beenToEnroute shouldBe true
  }
}
