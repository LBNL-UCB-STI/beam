package beam.agentsim.agents.ridehail

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit}
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{SimRunnerForTest, StuckFinder}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.scalatest.FunSpecLike

class RideHailManagerSpec extends TestKit(ActorSystem(
  name = "RideHailManagerSpec",
  config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
)) with BeamHelper with FunSpecLike {

  lazy val beamConfig = BeamConfig(system.settings.config)
  lazy val matsimConfig = new MatSimBeamConfigBuilder(system.settings.config).buildMatSimConf()

  lazy val beamScenario = loadScenario(beamConfig)
  lazy val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector = buildInjector(system.settings.config, scenario, beamScenario)
  lazy val beamServices = buildBeamServices(injector, scenario)

  describe("A RideHailManager") {
    it("should do something") {
      val scheduler = TestActorRef[BeamAgentScheduler](Props(new BeamAgentScheduler(beamConfig, Int.MaxValue, 10, new StuckFinder(beamConfig.beam.debug.stuckAgentDetection))))
      val props = Props(new RideHailManager(new RideHailManager(
        Id.create("GlobalRHM", classOf[RideHailManager]),
        beamServices,
        beamScenario,
        beamScenario.transportNetwork,
        beamServices.tollCalculator,
        beamServices.matsimServices.getScenario,
        beamServices.matsimServices.getEvents,
        scheduler,
        beamRouter,
        parkingManager,
        envelopeInUTM,
        activityQuadTreeBounds,
        rideHailSurgePricingManager,
        rideHailIterationHistory.oscillationAdjustedTNCIterationStats,
        beamSkimmer,
        routeHistory
      )))
      TestActorRef[RideHailManager](props)
    }

  }
}
