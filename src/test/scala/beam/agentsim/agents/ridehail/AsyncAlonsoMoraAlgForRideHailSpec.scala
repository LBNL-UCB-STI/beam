package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await

class AsyncAlonsoMoraAlgForRideHailSpec extends FlatSpec with Matchers with BeamHelper {

  "Running Async Alonso Mora Algorithm" must "creates a consistent plan" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec = 420
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.travelTimeDelayAsFraction= 0.2
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle = 1000
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val assignment = computeAssignment(config, "scenario1")
//    assert(assignment(0)._2.getId == "v2")
//    assignment(0)._1.requests.foreach(r => List("p1", "p2", "p4").contains(r.getId))
//    assert(assignment(1)._2.getId == "v1")
//    assert(assignment(1)._1.requests.head.getId == "p3")
  }

  "Running Async Alonso Mora Algorithm" must "Creates a consistent plan considering a geofence" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec = 420
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.travelTimeDelayAsFraction= 0.2
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle = 1000
        """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    val assignment = computeAssignment(config, "scenarioGeofence")
//    assert(assignment(0)._2.getId == "v2")
//    assert(assignment(0)._1.requests.head.getId == "p4")
//    assert(assignment(0)._1.requests.last.getId == "p1" || assignment(0)._1.requests.last.getId == "p2")
//    assert(assignment(1)._2.getId == "v1")
//    assert(assignment(1)._1.requests.head.getId == "p3")
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
    Await.result(alg.matchAndAssign(0), atMost = 10.minutes).toArray
  }

  "Running Async Alonso Mora Algorithm" must "scale" in {
    val config = ConfigFactory
      .parseString("""
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.transfer = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.car_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_transit_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.drive_transit_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_transit_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.ride_hail_pooled_intercept = 30.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.walk_intercept = 0.0
                     |beam.agentsim.agents.modalBehaviors.mulitnomialLogit.params.bike_intercept = 0.0
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.physsim.skipPhysSim = true
                     |beam.agentsim.lastIteration = 0
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec = 360
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.travelTimeDelayAsFraction= 0.5
                     |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle = 100
        """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-25k.conf"))
      .resolve()

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
//    val t0 = System.nanoTime()
//    services.controler.run()
//    val t1 = System.nanoTime()
//    println("Elapsed time: " + (t1 - t0)/1E9 + " sec")
  }
}
