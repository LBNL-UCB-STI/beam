package beam.agentsim.agents.vehicles

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.router.Modes.BeamMode.WALK
import beam.router.RoutingModel.{BeamLeg, EmptyBeamPath}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSpecLike, Matchers, _}

/**
  *
  */
class PassengerScheduleTest
    extends TestKit(ActorSystem("PassengerScheduleTest"))
    with FunSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ImplicitSender
    with MockitoSugar {
  val services: BeamServices = mock[BeamServices]

  describe("A PassengerSchedule") {

    it("should create an empty schedule") {

      val passengerSchedule: PassengerSchedule = PassengerSchedule()

      passengerSchedule.schedule.size should be(0)
    }

    it("should create a schedule for single passenger with one leg") {

      val vehicleId: Id[Vehicle] = Id.createVehicleId("dummyVehicle")
      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")

      val leg = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(VehiclePersonId(vehicleId, passengerPersonId), Vector(leg))

      passengerSchedule.schedule.size should be(1)
      passengerSchedule.schedule(leg).riders.size should ===(1)
      passengerSchedule.schedule(leg).boarders.size should ===(1)
      passengerSchedule.schedule(leg).alighters.size should ===(1)
    }
    it("should create a schedule for single passenger with many legs") {

      val vehicleId: Id[Vehicle] = Id.createVehicleId("dummyVehicle")
      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")

      val leg1 = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)
      val leg2 = BeamLeg(1L, WALK, 1L, EmptyBeamPath.path)
      val leg3 = BeamLeg(2L, WALK, 1L, EmptyBeamPath.path)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(VehiclePersonId(vehicleId, passengerPersonId), Vector(leg1, leg2, leg3))

      passengerSchedule.schedule.size should ===(3)

      passengerSchedule.schedule(leg1).riders.size should ===(1)
      passengerSchedule.schedule(leg1).boarders.size should ===(1)
      passengerSchedule.schedule(leg1).alighters.size should ===(0)

      passengerSchedule.schedule(leg2).riders.size should ===(1)
      passengerSchedule.schedule(leg2).boarders.size should ===(0)
      passengerSchedule.schedule(leg2).alighters.size should ===(0)

      passengerSchedule.schedule(leg3).riders.size should ===(1)
      passengerSchedule.schedule(leg3).boarders.size should ===(0)
      passengerSchedule.schedule(leg3).alighters.size should ===(1)
    }
    it("should create a schedule for many passengers with many legs") {

      val vehicleId1: Id[Vehicle] = Id.createVehicleId("dummyVehicle1")
      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")

      val vehicleId2: Id[Vehicle] = Id.createVehicleId("dummyVehicle2")
      val passengerPersonId2: Id[Person] = Id.createPersonId("passengerPerson2")

      val leg1 = BeamLeg(0L, WALK, 1L, EmptyBeamPath.path)
      val leg2 = BeamLeg(1L, WALK, 1L, EmptyBeamPath.path)
      val leg3 = BeamLeg(2L, WALK, 1L, EmptyBeamPath.path)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(VehiclePersonId(vehicleId1, passengerPersonId), Vector(leg1, leg2, leg3))
        .addPassenger(VehiclePersonId(vehicleId2, passengerPersonId2), Vector(leg2, leg3))

      passengerSchedule.schedule.size should ===(3)

      passengerSchedule.schedule(leg1).riders.size should ===(1)
      passengerSchedule.schedule(leg1).boarders.size should ===(1)
      passengerSchedule.schedule(leg1).alighters.size should ===(0)

      passengerSchedule.schedule(leg2).riders.size should ===(2)
      passengerSchedule.schedule(leg2).boarders.size should ===(1)
      passengerSchedule.schedule(leg2).alighters.size should ===(0)

      passengerSchedule.schedule(leg3).riders.size should ===(2)
      passengerSchedule.schedule(leg3).boarders.size should ===(0)
      passengerSchedule.schedule(leg3).alighters.size should ===(2)
    }
  }

  override def afterAll: Unit = {
    shutdown()
  }

}
