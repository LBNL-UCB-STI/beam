package beam.integration

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.events._
import beam.router.Modes.BeamMode
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.data.synthpop.models.Models.Household
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Leg, Person}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.mobsim.jdeqsim.Vehicle
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

class ChargingSpec extends FlatSpec with Matchers with BeamHelper {

  private val beamVilleCarId = Id.create("beamVilleCar", classOf[BeamVehicleType])
  private val filesPath = getClass.getResource("/files").getPath

  val config = ConfigFactory
    .parseString(
      s"""|beam.outputs.events.fileOutputFormats = xml
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 0
         |beam.agentsim.tuning.transitCapacity = 0.0
         |beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0
         |beam.agentsim.agents.vehicles.sharedFleets = []
         |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
         |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
         |beam.cosim.helics.timeStep = 300
         |beam.agentsim.taz.parkingFilePath = $filesPath"/taz-parking-ac-only.csv"
         |
      """.stripMargin
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  "Running a single person car-only scenario" must "catch charging events" in {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    var chargingPlugInEvents = 0
    var chargingPlugOutEvents = 0
    var totalEnergyInJoules = 0.0
    var totalSessionDuration = 0.0

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: ChargingPlugInEvent  => chargingPlugInEvents = chargingPlugInEvents + 1
                case e: ChargingPlugOutEvent => chargingPlugOutEvents = chargingPlugOutEvents + 1
                case e: RefuelSessionEvent =>
                  totalEnergyInJoules += e.energyInJoules
                  totalSessionDuration += e.sessionDuration
                case _ =>
                // TODO LinkEnterEvent, LinkLeaveEvent   to calculate the distance being travelled and divide by fuel
              }
            }
          })
        }
      }
    )

    val beamVilleCarEVType = beamScenario.vehicleTypes(beamVilleCarId)
    beamVilleCarEVType.primaryFuelType shouldBe Electricity

    val services = injector.getInstance(classOf[BeamServices])
    val transportNetwork = injector.getInstance(classOf[TransportNetwork])

    // single person in a single household in town
    val population = scenario.getPopulation
    (2 to 50).map(Id.create(_, classOf[Person])).foreach(population.removePerson)

    val households = scenario.getHouseholds
    (2 to 21).map(Id.create(_, classOf[Household])).foreach(households.getHouseholds.remove)

    assume(population.getPersons.size == 1, "A single person in the town")
    assume(households.getHouseholds.size == 1, "A single household in the town")

    households.getHouseholds.forEach {
      case (_, household) =>
        household.getMemberIds.removeIf(_ != Id.createPersonId(1))
        household.getVehicleIds.removeIf(_ != Id.create(2, classOf[Vehicle]))
    }

    // Only driving allowed
    val noCarModes = BeamMode.allModes.filter(_ != BeamMode.CAR).map(_.value.toLowerCase) mkString ","
    population.getPersons.forEach {
      case (personId, person) =>
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

    // TODO remove after debugging
    println(totalSessionDuration)
    println(totalEnergyInJoules)
    println(chargingPlugInEvents)
    println(chargingPlugOutEvents)

    assume(chargingPlugInEvents >= 1, "Something's wildly broken, I am not seeing any chargingPlugInEvents.")
    assume(chargingPlugOutEvents >= 1, "Something's wildly broken, I am not seeing any chargingPlugOutEvents.")

    chargingPlugInEvents should equal(chargingPlugOutEvents)
    totalSessionDuration shouldBe >=(chargingPlugInEvents * 100.0)
    totalEnergyInJoules shouldBe >=(chargingPlugInEvents * 1.5e6)
  }
}
