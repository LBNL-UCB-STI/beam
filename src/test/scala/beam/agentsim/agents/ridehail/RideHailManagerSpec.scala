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
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}
import org.scalatest.mockito.MockitoSugar

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
        beam.agentsim.agents.rideHail.allocationManager.name = POOLING
        """
          )
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with BeamHelper
    with ImplicitSender
    with FunSpecLike
    with MockitoSugar
    with BeforeAndAfterAll {

  describe("A RideHailManager") {

    it("should let me inquire for and reserve a ride, and pick me up") {
      val scheduler = TestActorRef[BeamAgentScheduler](schedulerProps)
      val parkingManager = TestActorRef[TrivialParkingManager](Props(new TrivialParkingManager()))
      val beamRouter = TestActorRef[BeamRouter](beamRouterProps)
      val rideHailManager = TestActorRef[RideHailManager](rideHailManagerProps(scheduler, parkingManager, beamRouter))
      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailManager)

      val me = beamServices.matsimServices.getScenario.getPopulation.getPersons.values.iterator.next
      val home = me.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity]
      val work = me.getSelectedPlan.getPlanElements.get(2).asInstanceOf[Activity]
      scheduler ! ScheduleTrigger(InitializeTrigger(home.getEndTime.toInt), self)

      scheduler ! StartSchedule(0)

      within(1 minute) {
        val trigger = expectMsgType[TriggerWithId]
        // I can only make a ride hail request when I am in the time window for my departure, not earlier
        val request = RideHailRequest(
          RideHailInquiry,
          PersonIdWithActorRef(me.getId, self),
          home.getCoord,
          home.getEndTime.toInt,
          work.getCoord
        )
        rideHailManager ! request
        expectMsgType[RideHailResponse]
        rideHailManager ! request.copy(requestType = ReserveRide)
        val rideHailResponse = expectMsgType[RideHailResponse]
        scheduler ! CompletionNotice(trigger.triggerId, rideHailResponse.triggersToSchedule)
        val boardVehicleTrigger = expectMsgType[TriggerWithId]
        scheduler ! CompletionNotice(boardVehicleTrigger.triggerId)
        val leaveVehicleTrigger = expectMsgType[TriggerWithId]
        scheduler ! CompletionNotice(leaveVehicleTrigger.triggerId)
        expectMsgType[CompletionNotice]
      }
    }

    //    it("should pool several rides") {
    //      val scheduler = TestActorRef[BeamAgentScheduler](schedulerProps)
    //      val parkingManager = TestActorRef[TrivialParkingManager](Props(new TrivialParkingManager()))
    //      val beamRouter = TestActorRef[BeamRouter](beamRouterProps)
    //      val rideHailManager = TestActorRef[RideHailManager](rideHailManagerProps(scheduler, parkingManager, beamRouter))
    //      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailManager)
    //
    //      val me = beamServices.matsimServices.getScenario.getPopulation.getPersons.values.iterator.next
    //      val home = me.getSelectedPlan.getPlanElements.get(0).asInstanceOf[Activity]
    //      val work = me.getSelectedPlan.getPlanElements.get(2).asInstanceOf[Activity]
    //      scheduler ! ScheduleTrigger(InitializeTrigger(home.getEndTime.toInt), self)
    //
    //      scheduler ! StartSchedule(0)
    //
    //      within(1 minute) {
    //        val trigger = expectMsgType[TriggerWithId]
    //        for (i <- 1 to 5) {
    //          val request = RideHailRequest(
    //            RideHailInquiry,
    //            PersonIdWithActorRef(Id.createPersonId(i), self),
    //            home.getCoord,
    //            home.getEndTime.toInt,
    //            work.getCoord
    //          )
    //          rideHailManager ! request
    //        }
    //        // I can only make a ride hail request when I am in the time window for my departure, not earlier
    //        println(expectMsgType[RideHailResponse])
    //        scheduler ! CompletionNotice(trigger.triggerId)
    //        expectMsgType[CompletionNotice]
    //      }
    //    }
  }

  lazy val beamExecutionConfig = setupBeamWithConfig(system.settings.config)
  lazy val beamConfig = beamExecutionConfig.beamConfig
  lazy val matsimConfig = beamExecutionConfig.matsimConfig
  lazy val beamScenario = loadScenario(beamConfig)
  lazy val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  lazy val injector = buildInjector(system.settings.config, scenario, beamScenario)
  lazy val beamServices = buildBeamServices(injector, scenario)

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

  val schedulerProps = Props(
    new BeamAgentScheduler(
      beamConfig,
      24 * 60 * 60,
      10,
      new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
    )
  )

  private def rideHailManagerProps(
    scheduler: TestActorRef[BeamAgentScheduler],
    parkingManager: TestActorRef[TrivialParkingManager],
    beamRouter: TestActorRef[BeamRouter]
  ) = {
    val routeHistory = mock[RouteHistory]
    when(routeHistory.getRoute(any(), any(), any())).thenReturn(None)
    val beamSkimmer = mock[BeamSkimmer]
    val rideHailSurgePricingManager = mock[RideHailSurgePricingManager]
    Props(
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
  }

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

}
