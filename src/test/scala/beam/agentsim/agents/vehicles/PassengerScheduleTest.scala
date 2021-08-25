package beam.agentsim.agents.vehicles

import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit}
import beam.router.Modes.BeamMode.WALK
import beam.router.model.{BeamLeg, BeamPath}
import beam.sim.BeamServices
import beam.utils.FileUtils
import org.apache.commons.io.IOUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.funspec.AnyFunSpecLike

import scala.collection.immutable.TreeMap

/**
  */
class PassengerScheduleTest
    extends TestKit(ActorSystem("PassengerScheduleTest"))
    with AnyFunSpecLike
    with BeforeAndAfterAll
    with Matchers
    with ImplicitSender {
  val services: BeamServices = Mockito.mock(classOf[BeamServices], withSettings().stubOnly())

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

  import beam.utils.json.AllNeededFormats._
  import io.circe._, io.circe.parser._

  it("should be able to find a beam leg (after fixing beam.agentsim.agents.vehicles.BeamLegOrdering)") {
    val basePath = System.getenv("PWD")
    val scheduleJsonStr =
      IOUtils.toString(new FileInputStream(s"$basePath/test/input/beamville/schedule.json"), StandardCharsets.UTF_8)
    val newBeamLegJsonStr =
      IOUtils.toString(new FileInputStream(s"$basePath/test/input/beamville/newLeg.json"), StandardCharsets.UTF_8)
    val program = for {
      scheduleWithLegs <- decode[List[(BeamLeg, PassengerSchedule.Manifest)]](scheduleJsonStr)
      newLeg           <- decode[BeamLeg](newBeamLegJsonStr)
      schedule = PassengerSchedule(TreeMap(scheduleWithLegs: _*)(BeamLegOrdering))
    } yield (schedule, newLeg)

    program match {
      case Left(value) =>
        fail(value.toString)
      case Right((schedule, newLeg)) =>
        schedule.schedule.get(newLeg) match {
          case None =>
            fail(s"Expected to find Manifest by leg '$newLeg'")
          case Some(value) =>
            require(value.riders.size == 1)
            require(value.riders.head.personId.toString == "Hello")
        }
    }

  }

  override def afterAll: Unit = {
    shutdown()
  }

}
