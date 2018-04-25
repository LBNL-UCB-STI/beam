package beam.agentsim.agents

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}
import akka.util.Timeout
import beam.agentsim.Resource.{CheckInResource, NotifyResourceIdle, RegisterResource}
import beam.agentsim.agents.BeamAgent.Finish
import beam.agentsim.agents.rideHail.RideHailingAgent
import beam.agentsim.agents.rideHail.RideHailingAgent._
import beam.agentsim.agents.vehicles.{BeamVehicle, PassengerSchedule}
import beam.agentsim.agents.vehicles.BeamVehicleType.Car
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.events.{ModeChoiceEvent, SpaceTime}
import beam.agentsim.scheduler.{BeamAgentScheduler, Trigger, TriggerWithId}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger, SchedulerProps, StartSchedule}
import beam.router.Modes.BeamMode
import beam.router.RoutingModel.{BeamLeg, BeamPath, EmptyBeamPath}
import beam.router.r5.NetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.events._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.vehicles._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.collection.concurrent.TrieMap

class RideHailingAgentSpec extends TestKit(ActorSystem("testsystem", ConfigFactory.parseString(
  """
  akka.log-dead-letters = 10
  akka.actor.debug.fsm = true
  akka.loglevel = debug
  """).withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory("test/input/beamville/beam.conf").resolve()))) with FunSpecLike
  with BeforeAndAfterAll with MockitoSugar with ImplicitSender {

  private implicit val timeout = Timeout(60, TimeUnit.SECONDS)
  val config = BeamConfig(system.settings.config)
  val eventsManager = new EventsManagerImpl()
  eventsManager.addHandler(new BasicEventHandler {
    override def handleEvent(event: Event): Unit = {
      self ! event
    }
  })

  val vehicles = TrieMap[Id[Vehicle], BeamVehicle]()

  val services: BeamServices = {
    val theServices = mock[BeamServices]
    when(theServices.beamConfig).thenReturn(config)
    when(theServices.vehicles).thenReturn(vehicles)
    val geo = new GeoUtilsImpl(theServices)
    when(theServices.geo).thenReturn(geo)
    theServices
  }

  case class TestTrigger(tick: Double) extends Trigger

  private val networkCoordinator = new NetworkCoordinator(config, VehicleUtils.createVehiclesContainer())
  networkCoordinator.loadNetwork()

  describe("A RideHailingAgent") {

    // Hopefully deterministic test, where we mock a router and give the agent just one option for its trip.
    it("should drive around when I tell him to") {
      val vehicleType = new VehicleTypeImpl(Id.create(1, classOf[VehicleType]))
      val vehicleId = Id.createVehicleId(1)
      val vehicle = new VehicleImpl(vehicleId, vehicleType)
      val beamVehicle = new BeamVehicle(new Powertrain(0.0), vehicle, None, Car)
      beamVehicle.registerResource(self)
      vehicles.put(vehicleId, beamVehicle)

      val scheduler = TestActorRef[BeamAgentScheduler](SchedulerProps(config, stopTick = 64800.0, maxWindow = 10.0))

      val rideHailingAgent = TestFSMRef(new RideHailingAgent(Id.create("1", classOf[RideHailingAgent]), scheduler, beamVehicle, new Coord(0.0, 0.0), eventsManager, services, networkCoordinator.transportNetwork))
      expectMsgType[RegisterResource]

      scheduler ! ScheduleTrigger(InitializeTrigger(0), rideHailingAgent)
      scheduler ! ScheduleTrigger(TestTrigger(28800), self)
      scheduler ! StartSchedule(0)
      expectMsgType[CheckInResource] // Idle agent is idle
      expectMsgType[PersonDepartureEvent] // Departs..
      expectMsgType[PersonEntersVehicleEvent] // ..enters vehicle

      var trigger = expectMsgType[TriggerWithId] // 28800
      scheduler ! ScheduleTrigger(TestTrigger(30000), self)
      val passengerSchedule = PassengerSchedule()
        .addLegs(Seq(BeamLeg(28800, BeamMode.CAR, 10000, BeamPath(Vector(), None, SpaceTime(0.0,0.0,28800), SpaceTime(0.0,0.0,38800), 10000))))
      rideHailingAgent ! ModifyPassengerSchedule(passengerSchedule)
      val modifyPassengerScheduleAck = expectMsgType[ModifyPassengerScheduleAck]
      modifyPassengerScheduleAck.triggersToSchedule.foreach(scheduler ! _)
      expectMsgType[VehicleEntersTrafficEvent]
      scheduler ! CompletionNotice(trigger.triggerId)

      // FIXME: Oops, I get a VehicleLeavesTrafficEvent for 38800 even though I can still interrupt the agent..
      expectMsgType[VehicleLeavesTrafficEvent]


      trigger = expectMsgType[TriggerWithId] // 30000

      // Now I want to interrupt the agent, and it will say that for any point in time after 28800,
      // I can tell it whatever I want. Even though it is already 30000 for me.

      rideHailingAgent ! Interrupt()
      val interruptedAt = expectMsgType[InterruptedAt]
      assert(interruptedAt.tick >= 28800)
      assert(rideHailingAgent.stateName == Interrupted)
      expectNoMsg()
      rideHailingAgent ! Resume()
      scheduler ! CompletionNotice(trigger.triggerId)

      expectMsgType[NotifyResourceIdle]

      rideHailingAgent ! Finish
    }

  }


  override def afterAll: Unit = {
    shutdown()
  }

}

