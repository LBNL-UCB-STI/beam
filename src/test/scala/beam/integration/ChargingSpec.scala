package beam.integration

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.events._
import beam.agentsim.infrastructure.charging.ChargingPointType
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
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

class ChargingSpec extends AnyFlatSpec with Matchers with BeamHelper {
  private val beamVilleCarId = Id.create("beamVilleCar", classOf[BeamVehicleType])
  private val vehicleId = Id.create(2, classOf[Vehicle])
  private val filesPath = s"${System.getenv("PWD")}/test/test-resources/beam/input"

  val config = ConfigFactory
    .parseString(
      s"""|beam.outputs.events.fileOutputFormats = csv
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 0
         |beam.agentsim.tuning.transitCapacity = 0.0
         |beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 0
         |beam.agentsim.agents.vehicles.sharedFleets = []
         |beam.agentsim.agents.vehicles.vehiclesFilePath = $filesPath"/vehicles-simple.csv"
         |beam.agentsim.agents.vehicles.vehicleTypesFilePath = $filesPath"/vehicleTypes-simple.csv"
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

    val chargingPlugInEvents: ArrayBuffer[Double] = new ArrayBuffer[Double]()
    val chargingPlugOutEvents: ArrayBuffer[Double] = new ArrayBuffer[Double]()
    val refuelSessionEvents: ArrayBuffer[(Double, Long, Double)] = new ArrayBuffer[(Double, Long, Double)]()
    var energyConsumed: Double = 0.0

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case ChargingPlugInEvent(_, _, _, `vehicleId`, fuelLevel, _) => chargingPlugInEvents += fuelLevel
                case ChargingPlugOutEvent(_, _, `vehicleId`, fuelLevel, _)   => chargingPlugOutEvents += fuelLevel
                case RefuelSessionEvent(
                    _,
                    stall,
                    energyInJoules,
                    _,
                    sessionDuration,
                    `vehicleId`,
                    _,
                    _
                    ) =>
                  refuelSessionEvents += (
                    (
                      energyInJoules,
                      sessionDuration.toLong,
                      ChargingPointType.getChargingPointInstalledPowerInKw(stall.chargingPointType.get)
                    )
                  )
                case e: PathTraversalEvent if e.vehicleId == vehicleId =>
                  energyConsumed += e.primaryFuelConsumed
                case _ =>
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

    // 3 person in a single household in the town
    val population = scenario.getPopulation
    (4 to 50).map(Id.create(_, classOf[Person])).foreach(population.removePerson)

    val households = scenario.getHouseholds
    (2 to 21).map(Id.create(_, classOf[Household])).foreach(households.getHouseholds.remove)

    assume(population.getPersons.size == 3, "Three persons in the household")
    assume(households.getHouseholds.size == 1, "A single household in the town")

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

    val chargingPlugInEventsAmount = chargingPlugInEvents.size
    val chargingPlugOutEventsAmount = chargingPlugOutEvents.size
    val totalEnergyInJoules = refuelSessionEvents.map(_._1).sum
    val totalSessionDuration = refuelSessionEvents.map(_._2).sum

    assume(totalEnergyInJoules > 0, "totalEnergyInJoules should be non zero")
    assume(totalSessionDuration > 0, "totalSessionDuration should be non zero")
    assume(energyConsumed > 0, "energyConsumed should be non zero")

    assume(chargingPlugInEventsAmount >= 1, "Something's wildly broken, I am not seeing enough chargingPlugInEvents.")
    assume(chargingPlugOutEventsAmount >= 1, "Something's wildly broken, I am not seeing enough chargingPlugOutEvents.")
    chargingPlugInEventsAmount should equal(chargingPlugOutEventsAmount)

    // ensure each refuel event is difference of amounts of fuel before and after charging
    refuelSessionEvents.zipWithIndex foreach {
      case ((energyAdded, _, _), id) =>
        chargingPlugInEvents(id) + energyAdded shouldBe chargingPlugOutEvents(id)
    }

    val energyChargedInKWh = refuelSessionEvents.map(_._1).sum / 3.6e+6
    val powerPerTime = refuelSessionEvents.map(s => s._2 / 3600.0 * s._3).sum
    energyChargedInKWh shouldBe (powerPerTime +- 0.01)
    // consumed energy should be more or less equal total added energy
    // TODO Hard to test this without ensuring an energy conservation mechanism
    // totalEnergyInJoules shouldBe (energyConsumed +- 1000)
  }
}
