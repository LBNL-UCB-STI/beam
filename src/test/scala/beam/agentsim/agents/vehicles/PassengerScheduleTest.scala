package beam.agentsim.agents.vehicles

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import beam.agentsim.agents.PersonAgent
import org.scalatest.{FlatSpec, FunSpecLike, Matchers, MustMatchers}
import beam.agentsim.util.MockAgents._
import beam.router.Modes.BeamMode.WALK
import beam.router.RoutingModel.{BeamLeg, BeamStreetPath}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.scalatest.mockito.MockitoSugar

/**
  *
  */
class PassengerScheduleTest extends TestKit(ActorSystem("testsystem")) with Matchers with FunSpecLike with ImplicitSender with MockitoSugar{
  val services: BeamServices = mock[BeamServices]

  describe("A PassengerSchedule") {

    it("should create an empty schedule") {

      val sched: PassengerSchedule = PassengerSchedule()

      sched.schedule.size should be(0)
    }

    it("should create a schedule for single passenger with one leg") {

      val vehicleId: Id[Vehicle] = Id.create("dummyVehicle",classOf[Vehicle])

      val leg = BeamLeg(0L, WALK, 1L, BeamStreetPath.empty)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(vehicleId, Vector(leg))

      sched.schedule.size should be(1)
      sched.schedule.get(leg).get.riders.size should ===(1)
      sched.schedule.get(leg).get.boarders.size should ===(1)
      sched.schedule.get(leg).get.alighters.size should ===(1)
    }
    it("should create a schedule for single passenger with many legs") {

      val vehicleId: Id[Vehicle]  = Id.create("dummyVehicle",classOf[Vehicle])

      val leg1 = BeamLeg(0L, WALK, 1L, BeamStreetPath.empty)
      val leg2 = BeamLeg(1L, WALK, 1L, BeamStreetPath.empty)
      val leg3 = BeamLeg(2L, WALK, 1L, BeamStreetPath.empty)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(vehicleId, Vector(leg1, leg2, leg3))

      sched.schedule.size should ===(3)

      sched.schedule.get(leg1).get.riders.size should ===(1)
      sched.schedule.get(leg1).get.boarders.size should ===(1)
      sched.schedule.get(leg1).get.alighters.size should ===(0)

      sched.schedule.get(leg2).get.riders.size should ===(1)
      sched.schedule.get(leg2).get.boarders.size should ===(0)
      sched.schedule.get(leg2).get.alighters.size should ===(0)

      sched.schedule.get(leg3).get.riders.size should ===(1)
      sched.schedule.get(leg3).get.boarders.size should ===(0)
      sched.schedule.get(leg3).get.alighters.size should ===(1)
    }
    it("should create a schedule for many passengers with many legs") {

      val vehicleId1: Id[Vehicle]  = Id.create("dummyVehicle1",classOf[Vehicle])
      val vehicleId2: Id[Vehicle]  = Id.create("dummyVehicle2",classOf[Vehicle])

      val leg1 = BeamLeg(0L, WALK, 1L, BeamStreetPath.empty)
      val leg2 = BeamLeg(1L, WALK, 1L, BeamStreetPath.empty)
      val leg3 = BeamLeg(2L, WALK, 1L, BeamStreetPath.empty)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(vehicleId1, Vector(leg1, leg2, leg3))
      sched.addPassenger(vehicleId2, Vector(leg2, leg3))

      sched.schedule.size should ===(3)

      sched.schedule.get(leg1).get.riders.size should ===(1)
      sched.schedule.get(leg1).get.boarders.size should ===(1)
      sched.schedule.get(leg1).get.alighters.size should ===(0)

      sched.schedule.get(leg2).get.riders.size should ===(2)
      sched.schedule.get(leg2).get.boarders.size should ===(1)
      sched.schedule.get(leg2).get.alighters.size should ===(0)

      sched.schedule.get(leg3).get.riders.size should ===(2)
      sched.schedule.get(leg3).get.boarders.size should ===(0)
      sched.schedule.get(leg3).get.alighters.size should ===(2)
    }
  }
}
