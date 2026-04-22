package beam.router.r5

import akka.actor.ActorRef
import akka.testkit.TestProbe
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{IntermodalUse, Location, RoutingRequest}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{CAR, CAR_HOV2, HOV2_TELEPORTATION, WALK}
import beam.router.{BeamRouter, RoutingWorker}
import beam.sflight.AbstractSfLightSpec
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.AppenderBase
import org.matsim.api.core.v01.Id
import org.slf4j.LoggerFactory
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable
import scala.concurrent.duration._

class R5WrapperSpec extends AbstractSfLightSpec("R5WrapperSpec") with Matchers {

  private lazy val carVehicleType = services.beamScenario.vehicleTypes(Id.create("Car", classOf[BeamVehicleType]))

  private def getR5Wrapper: R5Wrapper = {
    val probe = new TestProbe(system)
    services.beamRouter.tell(BeamRouter.GetWorker, probe.ref)
    val worker = probe.expectMsgType[ActorRef](20.seconds)
    worker.tell(RoutingWorker.GetR5Wrapper, probe.ref)
    probe.expectMsgType[R5Wrapper](20.seconds)
  }

  "R5Wrapper" should {

    "return only car routes when requestedMode is CAR" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(552065.6882372601, 4180855.582994787)
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          Id.createVehicleId("car"),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        streetVehicles = streetVehicles,
        requestedMode = Some(CAR),
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.foreach { itinerary =>
        itinerary.tripClassifier should be(CAR)
      }
      response.itineraries.exists(_.tripClassifier == CAR) should be(true)
      response.itineraries.exists(_.tripClassifier == WALK) should be(false)
    }

    "return only walk routes when requestedMode is WALK" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(552065.6882372601, 4180855.582994787)
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          Id.createVehicleId("car"),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        streetVehicles = streetVehicles,
        requestedMode = Some(WALK),
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.foreach { itinerary =>
        itinerary.tripClassifier should be(WALK)
      }
      response.itineraries.exists(_.tripClassifier == WALK) should be(true)
      response.itineraries.exists(_.tripClassifier == CAR) should be(false)
    }

    "return only drive-transit routes when requestedMode is DRIVE_TRANSIT" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(556473.040858, 4176278.494490) // downtown SF
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          Id.createVehicleId("car"),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        streetVehicles = streetVehicles,
        requestedMode = Some(BeamMode.DRIVE_TRANSIT),
        streetVehiclesUseIntermodalUse = IntermodalUse.Access,
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.exists(_.tripClassifier == BeamMode.DRIVE_TRANSIT) should be(true)
      response.itineraries.exists(_.tripClassifier == BeamMode.WALK_TRANSIT) should be(false)
      response.itineraries.exists(_.tripClassifier == WALK) should be(false)
    }

    "return only walk-transit routes when requestedMode is WALK_TRANSIT" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(556473.040858, 4176278.494490) // downtown SF
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          Id.createVehicleId("car"),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        streetVehicles = streetVehicles,
        requestedMode = Some(BeamMode.WALK_TRANSIT),
        streetVehiclesUseIntermodalUse = IntermodalUse.Access,
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.exists(_.tripClassifier == BeamMode.WALK_TRANSIT) should be(true)
      response.itineraries.exists(_.tripClassifier == BeamMode.DRIVE_TRANSIT) should be(false)
      response.itineraries.exists(_.tripClassifier == CAR) should be(false)
    }

    "return only teleportation routes when requestedMode is HOV2_TELEPORTATION" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(552065.6882372601, 4180855.582994787)
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          BeamVehicle.createId(Id.createPersonId("test-person"), Some("teleportationSharedVehicle-1")),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR_HOV2,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        streetVehicles = streetVehicles,
        requestedMode = Some(HOV2_TELEPORTATION),
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.foreach { itinerary =>
        itinerary.tripClassifier should be(HOV2_TELEPORTATION)
      }
      response.itineraries.exists(_.tripClassifier == HOV2_TELEPORTATION) should be(true)
      response.itineraries.exists(_.tripClassifier == WALK) should be(false)
    }

    "not create walk alternatives for HOV2_TELEPORTATION requests even when transit routing is enabled" in {
      val r5Wrapper = getR5Wrapper

      val origin = new Location(551642.4729978561, 4180839.138663753)
      val destination = new Location(552065.6882372601, 4180855.582994787)
      val time = 27840

      val streetVehicles = Vector(
        StreetVehicle(
          Id.createVehicleId("body"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          SpaceTime(origin, time),
          WALK,
          asDriver = true,
          needsToCalculateCost = false
        ),
        StreetVehicle(
          BeamVehicle.createId(Id.createPersonId("test-person"), Some("teleportationSharedVehicle-2")),
          carVehicleType.id,
          SpaceTime(origin, time),
          CAR_HOV2,
          asDriver = true,
          needsToCalculateCost = true
        )
      )
      val request = RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        streetVehicles = streetVehicles,
        requestedMode = Some(HOV2_TELEPORTATION),
        triggerId = 0
      )

      val response = r5Wrapper.calcRoute(request, buildDirectCarRoute = true, buildDirectWalkRoute = true)

      response.itineraries.exists(_.tripClassifier == WALK) should be(false)
    }
  }
}

// Custom Appender to capture log messages
class ListAppender extends AppenderBase[ILoggingEvent] {
  private val list = mutable.ListBuffer[String]()

  override def append(eventObject: ILoggingEvent): Unit = {
    list += eventObject.getFormattedMessage
  }

  def getAndClearLogs(): Seq[String] = {
    val result = list.toList
    list.clear()
    result
  }
}
