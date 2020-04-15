package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.router.skim.Skims
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, TimeoutException}

class AsyncAlonsoMoraAlgForRideHailSpec extends FlatSpec with Matchers with BeamHelper {

  "Running Async Alonso Mora Algorithm" must "creates a consistent plan" in {
    val config = ConfigFactory
      .parseString(
        """
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "VEHICLE_CENTRIC_MATCHING_FOR_RIDEHAIL"
                     |beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 360
                     |beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.2
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.numRequestsPerVehicle = 1000
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val assignment = computeAssignment(config, "scenario1")
    assert(assignment.nonEmpty)
  }

  "Running Async Alonso Mora Algorithm" must "Creates a consistent plan considering a geofence" in {
    val config = ConfigFactory
      .parseString(
        """
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "VEHICLE_CENTRIC_MATCHING_FOR_RIDEHAIL"
                     |beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 360
                     |beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.2
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.numRequestsPerVehicle = 1000
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val assignment = computeAssignment(config, "scenarioGeofence")
    assert(assignment.nonEmpty)
  }

  private def computeAssignment(config: Config, scenarioName: String) = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    implicit val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
        }
      }
    )
    implicit val services = injector.getInstance(classOf[BeamServices])
    implicit val actorRef = ActorRef.noSender
    Skims.setup
    val sc = scenarioName match {
      case "scenarioGeofence" => AlonsoMoraPoolingAlgForRideHailSpec.scenarioGeoFence()
      case _                  => AlonsoMoraPoolingAlgForRideHailSpec.scenario1()
    }
    val alg: AsyncAlonsoMoraAlgForRideHail =
      new AsyncAlonsoMoraAlgForRideHail(
        AlonsoMoraPoolingAlgForRideHailSpec.demandSpatialIndex(sc._2),
        sc._1,
        services
      )
    import scala.concurrent.duration._
    val assignment = try {
      Await.result(alg.matchAndAssign(0), atMost = 2.minutes)
    } catch {
      case _: TimeoutException => List()
    }
    assignment.toArray
  }
}
