package beam.agentsim.agents

import beam.agentsim.agents.modalbehaviors.DrivesVehicle
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, LinkEnterEvent, LinkLeaveEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsUtils
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.vehicles.Vehicle
import org.scalatest.Assertion
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

class DummyEventsHandler extends BasicEventHandler {
  val allEvents: ArrayBuffer[Event] = ArrayBuffer()
  override def handleEvent(event: Event): Unit = {
    allEvents += event
  }
}

class DrivesVehicleTest extends AnyFunSuite {
  test("processLinkEvents should work properly") {
    val linkIds = Array(1, 2, 3, 4)
    val linkTravelTime = Array(1.0, 2.0, 3.0, 4.0)
    val vehicleId = 1L

    val beamLeg = BeamLeg(
      0,
      BeamMode.CAR,
      0,
      BeamPath(
        linkIds = linkIds,
        linkTravelTime = linkTravelTime,
        transitStops = None,
        startPoint = SpaceTime.zero,
        endPoint = SpaceTime.zero.copy(time = 9), // We need endPoint.time - startPoint.time == linkTravelTime.tail.sum
        distanceInM = 10.0
      )
    )
    val handler = new DummyEventsHandler()
    val eventsManager = EventsUtils.createEventsManager
    eventsManager.addHandler(handler)

    DrivesVehicle.processLinkEvents(eventsManager, Id.createVehicleId(vehicleId), beamLeg)

    // If not like this, I still need to do sliding and other things which is already in another test
    assert(handler.allEvents.size == 6)
    assert(
      handler
        .allEvents(0)
        .toString == new LinkLeaveEvent(1.0, Id.createVehicleId(vehicleId), Id.createLinkId(1)).toString
    )

    assert(
      handler
        .allEvents(1)
        .toString == new LinkEnterEvent(1.0, Id.createVehicleId(vehicleId), Id.createLinkId(2)).toString
    )
    assert(
      handler
        .allEvents(2)
        .toString == new LinkLeaveEvent(3.0, Id.createVehicleId(vehicleId), Id.createLinkId(2)).toString
    )

    assert(
      handler
        .allEvents(3)
        .toString == new LinkEnterEvent(3.0, Id.createVehicleId(vehicleId), Id.createLinkId(3)).toString
    )
    assert(
      handler
        .allEvents(4)
        .toString == new LinkLeaveEvent(6.0, Id.createVehicleId(vehicleId), Id.createLinkId(3)).toString
    )

    assert(
      handler
        .allEvents(5)
        .toString == new LinkEnterEvent(6.0, Id.createVehicleId(vehicleId), Id.createLinkId(4)).toString
    )
  }

  test("processLinkEvents should work as before") {
    compareNewVsOld(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(
          linkIds = Vector(1, 2, 3, 4, 5),
          linkTravelTime = Vector(5, 5, 5, 5, 5),
          transitStops = None,
          startPoint = SpaceTime.zero,
          endPoint = SpaceTime.zero.copy(time = 20),
          distanceInM = 10.0
        )
      )
    )

    compareNewVsOld(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(
          linkIds = Vector(1, 2, 3, 4),
          linkTravelTime = Vector(1, 2, 3, 4),
          transitStops = None,
          startPoint = SpaceTime.zero,
          endPoint = SpaceTime.zero.copy(time = 9),
          distanceInM = 10.0
        )
      )
    )

    compareNewVsOld(
      BeamLeg(
        0,
        BeamMode.CAR,
        0,
        BeamPath(
          linkIds = Vector(1),
          linkTravelTime = Vector(1),
          transitStops = None,
          startPoint = SpaceTime.zero,
          endPoint = SpaceTime.zero,
          distanceInM = 10.0
        )
      )
    )
  }

  def compareNewVsOld(beamLeg: BeamLeg): Assertion = {
    var newEvents: Vector[Event] = null;
    {
      val handler = new DummyEventsHandler()
      val eventsManager = EventsUtils.createEventsManager
      eventsManager.addHandler(handler)
      DrivesVehicle.processLinkEvents(eventsManager, Id.createVehicleId(1L), beamLeg)
      newEvents = handler.allEvents.toVector

    }

    var originalEvents: Vector[Event] = null;
    {
      val handler = new DummyEventsHandler()
      val eventsManager = EventsUtils.createEventsManager
      eventsManager.addHandler(handler)
      originalProcessLinkEvents(eventsManager, Id.createVehicleId(1L), beamLeg)
      originalEvents = handler.allEvents.toVector
    }

    assert(newEvents == originalEvents)
  }

  def originalProcessLinkEvents(eventsManager: EventsManager, vehicleId: Id[Vehicle], leg: BeamLeg): Unit = {
    val path = leg.travelPath
    if (path.linkTravelTime.nonEmpty & path.linkIds.size > 1) {
      // FIXME once done with debugging, make this code faster
      // We don't need the travel time for the last link, so we drop it (dropRight(1))
      val avgTravelTimeWithoutLast = path.linkTravelTime.dropRight(1)
      val links = path.linkIds
      val linksWithTime = links.sliding(2).zip(avgTravelTimeWithoutLast.iterator)

      var curTime = leg.startTime
      linksWithTime.foreach {
        case (Seq(from, to), timeAtNode) =>
          curTime = math.round(curTime + timeAtNode).intValue()
          eventsManager.processEvent(new LinkLeaveEvent(curTime, vehicleId, Id.createLinkId(from)))
          eventsManager.processEvent(new LinkEnterEvent(curTime, vehicleId, Id.createLinkId(to)))
      }
    }
  }
}
