package beam.agentsim.agents.vehicles

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.router.Modes.BeamMode.WALK
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
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
  val services: BeamServices = mock[BeamServices](withSettings().stubOnly())

  describe("A PassengerSchedule") {

    it("should create an empty schedule") {

      val passengerSchedule: PassengerSchedule = PassengerSchedule()

      passengerSchedule.schedule.size should be(0)
    }

    it("should create a schedule for single passenger with one leg") {

      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")

      val leg = BeamLeg(0, WALK, 1, BeamPath.empty)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(PersonIdWithActorRef(passengerPersonId, ActorRef.noSender), Vector(leg))

      passengerSchedule.schedule.size should be(1)
      passengerSchedule.schedule(leg).riders.size should ===(1)
      passengerSchedule.schedule(leg).boarders.size should ===(1)
      passengerSchedule.schedule(leg).alighters.size should ===(1)
    }
    it("should create a schedule for single passenger with many legs") {

      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")

      val leg1 = BeamLeg(0, WALK, 1, BeamPath.empty)
      val leg2 = BeamLeg(1, WALK, 1, BeamPath.empty)
      val leg3 = BeamLeg(2, WALK, 1, BeamPath.empty)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(PersonIdWithActorRef(passengerPersonId, ActorRef.noSender), Vector(leg1, leg2, leg3))

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

      val passengerPersonId: Id[Person] = Id.createPersonId("passengerPerson")
      val passengerPersonId2: Id[Person] = Id.createPersonId("passengerPerson2")

      val leg1 = BeamLeg(0, WALK, 1, BeamPath.empty)
      val leg2 = BeamLeg(1, WALK, 1, BeamPath.empty)
      val leg3 = BeamLeg(2, WALK, 1, BeamPath.empty)

      val passengerSchedule: PassengerSchedule = PassengerSchedule()
        .addPassenger(PersonIdWithActorRef(passengerPersonId, ActorRef.noSender), Vector(leg1, leg2, leg3))
        .addPassenger(PersonIdWithActorRef(passengerPersonId2, ActorRef.noSender), Vector(leg2, leg3))

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
