package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKitBase}
import akka.util.Timeout
import beam.agentsim.Resource.NotifyVehicleIdle
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.PersonAgent.DrivingInterrupted
import beam.agentsim.agents.modalbehaviors.DrivesVehicle.{AlightVehicleTrigger, BoardVehicleTrigger, StopDriving}
import beam.agentsim.agents.ridehail.RideHailAgent
import beam.agentsim.agents.ridehail.RideHailAgent._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.infrastructure.{ChargingNetworkManager, ZonalParkingManager}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger}
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import beam.router.osm.TollCalculator
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, StuckFinder, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.scalatest.{BeforeAndAfter, FunSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class RideHailAgentSpec
    extends FunSpecLike
    with TestKitBase
    with SimRunnerForTest
    with MockitoSugar
    with ImplicitSender
    with BeforeAndAfter
    with BeamvilleFixtures {

  private implicit val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        akka.test.timefactor = 2
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("RideHailAgentSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  lazy val eventMgr = new EventsManagerImpl()

  private lazy val zonalParkingManager = system.actorOf(
    ZonalParkingManager.props(beamConfig, beamScenario.tazTreeMap, services.geo, services.beamRouter, boundingBox),
    "ParkingManager"
  )

  /*private lazy val chargingNetworkManager = (scheduler: ActorRef) =>
    system.actorOf(Props(new ChargingNetworkManager(services, beamScenario, scheduler)))*/

  case class TestTrigger(tick: Int) extends Trigger

  describe("A RideHailAgent") {

    def moveTo30000(scheduler: ActorRef, rideHailAgent: ActorRef) = {
      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailAgent)
      scheduler ! ScheduleTrigger(TestTrigger(28800), self)
      scheduler ! StartSchedule(0)
      expectMsgType[PersonDepartureEvent] // Departs..
      expectMsgType[PersonEntersVehicleEvent] // ..enters vehicle
      val notify = expectMsgType[NotifyVehicleIdle]
      rideHailAgent ! NotifyVehicleResourceIdleReply(notify.triggerId, Vector())

      val trigger = expectMsgType[TriggerWithId] // 28800
      scheduler ! ScheduleTrigger(TestTrigger(30000), self)
      val passengerSchedule = PassengerSchedule()
        .addLegs(
          Seq(
            BeamLeg(
              28800,
              BeamMode.CAR,
              10000,
              BeamPath(
                Vector(1),
                Vector(1),
                None,
                SpaceTime(0.0, 0.0, 28800),
                SpaceTime(0.0, 0.0, 28800),
                10000
              )
            ),
            BeamLeg(
              38800,
              BeamMode.CAR,
              10000,
              BeamPath(
                Vector(1),
                Vector(1),
                None,
                SpaceTime(0.0, 0.0, 38800),
                SpaceTime(0.0, 0.0, 38800),
                10000
              )
            )
          )
        )
        .addPassenger(
          PersonIdWithActorRef(Id.createPersonId(1), self),
          Seq(
            BeamLeg(
              38800,
              BeamMode.CAR,
              10000,
              BeamPath(
                Vector(1),
                Vector(1),
                None,
                SpaceTime(0.0, 0.0, 38800),
                SpaceTime(0.0, 0.0, 38800),
                10000
              )
            )
          )
        )
      rideHailAgent ! Interrupt(1, 30000)
      expectMsgType[InterruptedWhileIdle]
      rideHailAgent ! ModifyPassengerSchedule(passengerSchedule, 30000)
      rideHailAgent ! Resume
      val modifyPassengerScheduleAck = expectMsgType[ModifyPassengerScheduleAck]
      modifyPassengerScheduleAck.triggersToSchedule.foreach(scheduler ! _)
      expectMsgType[VehicleEntersTrafficEvent]
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[TriggerWithId] // 30000
    }

    it("should drive around when I tell him to") {
      val vehicleId = Id.createVehicleId(1)
      val beamVehicle =
        new BeamVehicle(
          vehicleId,
          new Powertrain(0.0),
          beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
        )
      beamVehicle.setManager(Some(self))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 64800,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val rideHailAgent = TestFSMRef(
        new RideHailAgent(
          Id.create("1", classOf[RideHailAgent]),
          self,
          scheduler,
          beamVehicle,
          new Coord(0.0, 0.0),
          None,
          None,
          eventMgr,
          zonalParkingManager,
          self,
          services,
          beamScenario,
          beamScenario.transportNetwork,
          tollCalculator = new TollCalculator(beamConfig)
        )
      )

      var trigger = moveTo30000(scheduler, rideHailAgent)

      // Now I want to interrupt the agent, and it will say that for any point in time after 28800,
      // I can tell it whatever I want. Even though it is already 30000 for me.

      rideHailAgent ! Interrupt(1, 30000)
      val interruptedAt = expectMsgType[InterruptedWhileDriving]
      assert(interruptedAt.currentPassengerScheduleIndex == 0) // I know this agent hasn't picked up the passenger yet
      assert(rideHailAgent.stateName == DrivingInterrupted)
      expectNoMessage()
      // Still, I tell it to resume
      rideHailAgent ! Resume
      scheduler ! ScheduleTrigger(TestTrigger(50000), self)
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]
      expectMsgType[VehicleEntersTrafficEvent]

      trigger = expectMsgType[TriggerWithId] // NotifyLegStartTrigger
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      val notifyVehicleIdle = expectMsgType[NotifyVehicleIdle]

      trigger = expectMsgType[TriggerWithId] // NotifyLegEndTrigger
      scheduler ! CompletionNotice(trigger.triggerId)

      rideHailAgent ! NotifyVehicleResourceIdleReply(notifyVehicleIdle.triggerId, Vector[ScheduleTrigger]())

      trigger = expectMsgType[TriggerWithId] // 50000
      scheduler ! CompletionNotice(trigger.triggerId)

      rideHailAgent ! Finish
      expectMsgType[CompletionNotice]
    }

    it("should let me interrupt it and tell it to cancel its job") {
      val vehicleId = Id.createVehicleId(1)
      val beamVehicle =
        new BeamVehicle(
          vehicleId,
          new Powertrain(0.0),
          beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
        )
      beamVehicle.setManager(Some(self))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 64800,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val rideHailAgent = TestFSMRef(
        new RideHailAgent(
          Id.create("1", classOf[RideHailAgent]),
          self,
          scheduler,
          beamVehicle,
          new Coord(0.0, 0.0),
          None,
          None,
          eventMgr,
          zonalParkingManager,
          self,
          services,
          beamScenario,
          beamScenario.transportNetwork,
          tollCalculator = new TollCalculator(beamConfig)
        )
      )

      var trigger = moveTo30000(scheduler, rideHailAgent)

      // Now I want to interrupt the agent, and it will say that for any point in time after 28800,
      // I can tell it whatever I want. Even though it is already 30000 for me.

      rideHailAgent ! Interrupt(1, 30000)
      val interruptedAt = expectMsgType[InterruptedWhileDriving]
      assert(interruptedAt.currentPassengerScheduleIndex == 0) // I know this agent hasn't picked up the passenger yet
      assert(rideHailAgent.stateName == DrivingInterrupted)
      expectNoMessage()
      // I tell it to do nothing instead
      rideHailAgent ! StopDriving(30000)
      assert(rideHailAgent.stateName == IdleInterrupted)

      rideHailAgent ! Resume // That's the opposite of Interrupt(), not resume driving
      scheduler ! ScheduleTrigger(TestTrigger(50000), self)
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[PathTraversalEvent]

      expectMsgType[VehicleLeavesTrafficEvent]

      expectMsgType[PathTraversalEvent]

      expectMsgType[NotifyVehicleIdle]

      trigger = expectMsgType[TriggerWithId] // 50000
      scheduler ! CompletionNotice(trigger.triggerId)

      rideHailAgent ! Finish
      expectMsgType[CompletionNotice]
    }

    it("won't let me cancel its job after it has picked up passengers") {
      val vehicleId = Id.createVehicleId(1)
      val beamVehicle =
        new BeamVehicle(
          vehicleId,
          new Powertrain(0.0),
          beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
        )
      beamVehicle.setManager(Some(self))

      val scheduler = TestActorRef[BeamAgentScheduler](
        SchedulerProps(
          beamConfig,
          stopTick = 64800,
          maxWindow = 10,
          new StuckFinder(beamConfig.beam.debug.stuckAgentDetection)
        )
      )

      val rideHailAgent = TestFSMRef(
        new RideHailAgent(
          Id.create("1", classOf[RideHailAgent]),
          self,
          scheduler,
          beamVehicle,
          new Coord(0.0, 0.0),
          None,
          None,
          eventMgr,
          zonalParkingManager,
          self,
          services,
          beamScenario,
          beamScenario.transportNetwork,
          tollCalculator = new TollCalculator(beamConfig)
        )
      )

      var trigger = moveTo30000(scheduler, rideHailAgent)
      scheduler ! ScheduleTrigger(TestTrigger(40000), self)
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      expectMsgType[VehicleEntersTrafficEvent]

      trigger = expectMsgPF() {
        case t @ TriggerWithId(BoardVehicleTrigger(38800, _), _) =>
          t
      }
      scheduler ! CompletionNotice(trigger.triggerId)
      trigger = expectMsgPF() {
        case t @ TriggerWithId(TestTrigger(40000), _) =>
          t
      }

      rideHailAgent ! Interrupt(1, 30000)
      val interruptedAt = expectMsgType[InterruptedWhileDriving]
      assert(interruptedAt.currentPassengerScheduleIndex == 1) // I know this agent has now picked up the passenger
      assert(rideHailAgent.stateName == DrivingInterrupted)
      expectNoMessage()
      // Don't StopDriving() here because we have a Passenger and we don't know how that works yet.
      rideHailAgent ! Resume
      scheduler ! ScheduleTrigger(TestTrigger(50000), self)
      scheduler ! CompletionNotice(trigger.triggerId)
      expectMsgType[VehicleLeavesTrafficEvent]
      expectMsgType[PathTraversalEvent]
      val notifyVehicleIdle = expectMsgType[NotifyVehicleIdle]
      rideHailAgent ! NotifyVehicleResourceIdleReply(notifyVehicleIdle.triggerId, Vector())
      trigger = expectMsgPF() {
        case t @ TriggerWithId(AlightVehicleTrigger(48800, _, _), _) =>
          t
      }
      scheduler ! CompletionNotice(trigger.triggerId)
      trigger = expectMsgPF() {
        case t @ TriggerWithId(TestTrigger(50000), _) =>
          t
      }
      scheduler ! CompletionNotice(trigger.triggerId)
      rideHailAgent ! Finish

      expectMsgType[CompletionNotice]
    }

  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    eventMgr.addHandler(new BasicEventHandler {
      override def handleEvent(event: Event): Unit = {
        self ! event
      }
    })
  }

  after {
    import scala.concurrent.duration._
    import scala.language.postfixOps
    //we need to prevent getting this CompletionNotice from the Scheduler in the next test
    receiveWhile(1000 millis) {
      case _: CompletionNotice =>
    }
  }

  override def afterAll(): Unit = {
    shutdown()
    super.afterAll()
  }

}
