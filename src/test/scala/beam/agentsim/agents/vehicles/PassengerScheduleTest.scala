package beam.agentsim.agents.vehicles

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit}
import beam.agentsim.agents.PersonAgent
import org.scalatest.{FlatSpec, FunSpecLike, Matchers, MustMatchers}
import beam.agentsim.util.MockAgents._
import beam.router.Modes.BeamMode.WALK
import beam.router.RoutingModel.{BeamLeg, BeamPath, EmptyBeamPath}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.matsim.api.core.v01.population.Person
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
      val passengerPersonId: Id[Person] = Id.create("passengerPerson",classOf[Person])

      val leg = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(VehiclePersonId(vehicleId, passengerPersonId), Vector(leg))

      sched.schedule.size should be(1)
      sched.schedule.get(leg).get.riders.size should ===(1)
      sched.schedule.get(leg).get.boarders.size should ===(1)
      sched.schedule.get(leg).get.alighters.size should ===(1)
    }
    it("should create a schedule for single passenger with many legs") {

      val vehicleId: Id[Vehicle]  = Id.create("dummyVehicle",classOf[Vehicle])
      val passengerPersonId: Id[Person] = Id.create("passengerPerson",classOf[Person])

      val leg1 = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)
      val leg2 = BeamLeg(1L, WALK, 1L, EmptyBeamPath.path)
      val leg3 = BeamLeg(2L, WALK, 1L, EmptyBeamPath.path)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(VehiclePersonId(vehicleId, passengerPersonId), Vector(leg1, leg2, leg3))

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
      val passengerPersonId: Id[Person] = Id.create("passengerPerson",classOf[Person])

      val vehicleId2: Id[Vehicle]  = Id.create("dummyVehicle2",classOf[Vehicle])
      val passengerPersonId2: Id[Person] = Id.create("passengerPerson2",classOf[Person])

      val leg1 = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)
      val leg2 = BeamLeg(1L, WALK, 1L, EmptyBeamPath.path)
      val leg3 = BeamLeg(2L, WALK, 1L, EmptyBeamPath.path)

      val sched: PassengerSchedule = PassengerSchedule()

      sched.addPassenger(VehiclePersonId(vehicleId1, passengerPersonId), Vector(leg1, leg2, leg3))
      sched.addPassenger(VehiclePersonId(vehicleId2, passengerPersonId2), Vector(leg2, leg3))

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
