package beam.agentsim.agents.ridehail

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import beam.agentsim.agents.InitializeTrigger
import beam.agentsim.agents.vehicles.PersonIdWithActorRef
import beam.agentsim.infrastructure.TrivialParkingManager
import beam.agentsim.scheduler.BeamAgentScheduler
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, StartSchedule}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.router.{BeamRouter, BeamSkimmer, RouteHistory}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.utils.StuckFinder
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.FunSpecLike
import org.scalatest.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._
import scala.concurrent.duration._
import scala.language.postfixOps

class RideHailManagerSpec
    extends TestKit(
      ActorSystem(
        name = "RideHailManagerSpec",
        config = ConfigFactory
          .parseString(
            """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        beam.agentsim.agents.rideHail.initialization.procedural.fractionOfInitialVehicleFleet = 1.0
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with BeamHelper
    with ImplicitSender
    with FunSpecLike
    with MockitoSugar {
  lazy val beamExecutionConfig = setupBeamWithConfig(system.settings.config)
  lazy val beamConfig = beamExecutionConfig.beamConfig
  lazy val matsimConfig = beamExecutionConfig.matsimConfig
  lazy val beamScenario = loadScenario(beamConfig)
  lazy val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector = buildInjector(system.settings.config, scenario, beamScenario)
  lazy val beamServices = buildBeamServices(injector, scenario)

  describe("A RideHailManager") {
    it("should do something") {
      val scheduler = TestActorRef[BeamAgentScheduler](
        Props(
          new BeamAgentScheduler(
            beamConfig,
            24 * 60 * 60,
            10,
            new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
          )
        )
      )
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
      when(routeHistory.getRoute(any(), any(), any())).thenReturn(None)
      val beamSkimmer = mock[BeamSkimmer]
      val rideHailSurgePricingManager = mock[RideHailSurgePricingManager]
      val props = Props(
        new RideHailManager(
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
        )
      )
      val rideHailManager = TestActorRef[RideHailManager](props)
      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailManager)

      val me = beamServices.matsimServices.getScenario.getPopulation.getPersons.values.iterator.next
      val home = me.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity]
      val work = me.getSelectedPlan.getPlanElements.get(2).asInstanceOf[Activity]
      scheduler ! ScheduleTrigger(InitializeTrigger(home.getEndTime.toInt), self)

      scheduler ! StartSchedule(0)

      within(1 minute) {
        val trigger = expectMsgType[TriggerWithId]
        val request = RideHailRequest(
          RideHailInquiry,
          PersonIdWithActorRef(me.getId, self),
          home.getCoord,
          home.getEndTime.toInt,
          work.getCoord
        )
        rideHailManager ! request
        expectMsgType[RideHailResponse]
        scheduler ! CompletionNotice(trigger.triggerId)
        expectMsgType[CompletionNotice]
      }
    }

  }
}
