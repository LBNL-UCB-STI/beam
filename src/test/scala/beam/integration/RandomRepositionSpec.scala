package beam.integration

import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.router.Modes.BeamMode
import beam.router.skim.TAZSkimmerEvent
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.population.PopulationAdjustment.EXCLUDED_MODES
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import beam.utils.FileUtils
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.IterationStartsEvent
import org.matsim.core.controler.listener.IterationStartsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.vehicles.Vehicle
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable.ListBuffer

class RandomRepositionSpec extends FlatSpec with Matchers with BeamHelper {
  "Reposition scenario" must "results at least a vehicle driven by repositioning manager" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.actorSystemName = "CarSharingSpec"
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.outputs.writeSkimsInterval = 1
                     |beam.agentsim.agents.rideHail.repositioningManager.name="DEFAULT_REPOSITIONING_MANAGER"
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
                     |      name = "random-algorithm"
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
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val repositioningVehicleIds = new ListBuffer[Id[Vehicle]]()
    var repositioning = 0
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().toInstance(new BasicEventHandler {
            override def handleEvent(event: Event): Unit = {
              event match {
                case e: PathTraversalEvent if e.mode == BeamMode.CAR && repositioningVehicleIds.contains(e.vehicleId) =>
                  repositioning += 1
                case e: TAZSkimmerEvent if e.actor == "RepositionManager" =>
                  repositioningVehicleIds += e.vehicleId

                case _ =>
              }
            }
          })
          addControlerListenerBinding().toInstance(new IterationStartsListener {
            override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
              event.getIteration
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

    assume(repositioning > 0, "at least one car found with repositioning")
  }
}
