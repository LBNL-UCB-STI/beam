package beam.agentsim.agents.ridehail

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.infrastructure.TrivialParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.router.{BeamRouter, BeamSkimmer, RouteHistory}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.scalatest.FunSpecLike
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.language.postfixOps

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
)) with BeamHelper with ImplicitSender with FunSpecLike with MockitoSugar {
  lazy val beamExecutionConfig = setupBeamWithConfig(system.settings.config)
  lazy val beamConfig = beamExecutionConfig.beamConfig
  lazy val matsimConfig = beamExecutionConfig.matsimConfig
  lazy val beamScenario = loadScenario(beamConfig)
  lazy val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector = buildInjector(system.settings.config, scenario, beamScenario)
  lazy val beamServices = buildBeamServices(injector, scenario)

  describe("A RideHailManager") {
    it("should do something") {
      val scheduler = TestActorRef[BeamAgentScheduler](Props(new BeamAgentScheduler(beamConfig, 24*60*60, 10, new StuckFinder(beamConfig.beam.debug.stuckAgentDetection))))
      val parkingManager = TestActorRef[TrivialParkingManager](Props(new TrivialParkingManager()))
      val beamRouterProps = BeamRouter.props(
        beamScenario,
        beamScenario.transportNetwork,
        beamScenario.network,
        beamServices.networkHelper,
        new GeoUtilsImpl(beamConfig),
        scenario,
        scenario.getTransitVehicles,
        beamServices.fareCalculator,
        beamServices.tollCalculator
      )
      val beamRouter = TestActorRef[BeamRouter](beamRouterProps)
      val routeHistory = mock[RouteHistory]
      val beamSkimmer = mock[BeamSkimmer]
      val rideHailSurgePricingManager = mock[RideHailSurgePricingManager]
      val props = Props(new RideHailManager(
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
        rideHailSurgePricingManager,
        None,
        beamSkimmer,
        routeHistory
      ))
      val rideHailManager = TestActorRef[RideHailManager](props)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailManager)
      scheduler ! StartSchedule(0)
      within(1 minute) {
        expectMsgType[CompletionNotice]
      }
    }

  }
}
