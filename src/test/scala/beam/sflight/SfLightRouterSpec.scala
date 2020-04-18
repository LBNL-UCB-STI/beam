package beam.sflight

import akka.actor.Status.Failure
import akka.actor._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, RIDE_HAIL, RIDE_HAIL_TRANSIT, TRAM, WALK, WALK_TRANSIT}
import beam.router.model.{BeamLeg, BeamPath, BeamTrip}
import beam.router.{BeamRouter, Modes}
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest._

import scala.language.postfixOps

class SfLightRouterSpec extends AbstractSfLightSpec("SfLightRouterSpec") with Inside with LoneElement {
  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(583152.4334365112, 4139386.503815964)
      val destination = new BeamRouter.Location(572710.8214231567, 4142569.0802786923)
      val time = 25740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      val walkTrip = response.itineraries.find(_.tripClassifier == WALK).getOrElse(fail)
      val routedStartTime = walkTrip.beamLegs.head.startTime
      assert(routedStartTime == time)
    }

    "respond with a transit route to a second reasonable RoutingRequest" in {
      val origin = services.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = services.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
      val transitOption = response.itineraries.find(_.tripClassifier == WALK_TRANSIT).get
      assertMakesSense(transitOption.toBeamTrip)
      assert(transitOption.totalTravelTimeInSecs == 1116)
      assert(transitOption.legs(1).beamLeg.mode == TRAM)
      assert(transitOption.costEstimate == 2.75)
      assert(transitOption.legs.head.beamLeg.startTime == 25992)
    }

    "transit-route me to my destination vehicle, and to my final destination even if that's where I started" in {
      val origin = services.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val vehicleLocation = services.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val destination = origin
      val time = 25740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("116378-2"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(vehicleLocation, 0),
            Modes.BeamMode.CAR,
            asDriver = true
          ),
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        ),
        streetVehiclesUseIntermodalUse = Egress
      )
      val response = expectMsgType[RoutingResponse]

      val transitOption = response.itineraries.find(_.tripClassifier == DRIVE_TRANSIT).get
      assertMakesSense(transitOption.toBeamTrip)
      assert(transitOption.totalTravelTimeInSecs > 1000) // I have to get my car
      assert(!response.itineraries.exists(_.tripClassifier == WALK)) // I have to get my car
    }

    "respond with a ride-hail+transit route to a reasonable RoutingRequest" in {
      val origin = services.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = services.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("rideHailVehicle-person=17673-0"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = false
          ),
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        ),
        streetVehiclesUseIntermodalUse = AccessAndEgress
      )
      val response = expectMsgType[RoutingResponse]
      val rideHailTransitOption = response.itineraries.find(_.tripClassifier == RIDE_HAIL_TRANSIT).get
      assert(rideHailTransitOption.legs.count(l => l.beamLeg.mode == CAR) == 2, "Access and egress by car")
    }

    "respond with fast travel time for a fast bike" in {
      val fastBike = beamScenario.vehicleTypes(Id.create("FAST-BIKE", classOf[BeamVehicleType]))
      val expectedSpeed = 20
      assume(fastBike.maxVelocity.get == expectedSpeed)

      val origin = services.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = services.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("0"),
            fastBike.id,
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.BIKE,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      val bikeTrip = response.itineraries.find(_.tripClassifier == BIKE).getOrElse(fail)
      val routedStartTime = bikeTrip.beamLegs.head.startTime
      assert(routedStartTime == time)
      val actualSpeed = bikeTrip.beamLegs.head.travelPath.distanceInM / bikeTrip.totalTravelTimeInSecs
      assert(Math.abs(actualSpeed - expectedSpeed) < 4) // Difference probably due to start/end link
    }

    "respond with a fallback walk route to a RoutingRequest where walking would take approx. 8 hours" in {
      val origin = new BeamRouter.Location(626575.0322098453, 4181202.599243111)
      val destination =
        new BeamRouter.Location(607385.7148858022, 4172426.3760835854)
      val time = 25860
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-56658-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a route to yet another reasonable RoutingRequest" in {
      val origin =
        new BeamRouter.Location(583117.0300037456, 4168059.6668392466)
      val destination =
        new BeamRouter.Location(579985.712067158, 4167298.6137483735)
      val time = 20460
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-80672-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
    }

    "respond with a ride hailing route to a reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(551642.4729978561, 4180839.138663753)
      val destination =
        new BeamRouter.Location(552065.6882372601, 4180855.582994787)
      val time = 19740
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("rideHailVehicle-person=17673-0"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = false
          ),
          StreetVehicle(
            Id.createVehicleId("body-17673-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == RIDE_HAIL))
    }

    "respond with a walk and a car route for going from downtown SF to Treasure Island" in {
      val origin = services.geo.wgs2Utm(new Coord(-122.439194, 37.785368))
      val destination = services.geo.wgs2Utm(new Coord(-122.3712, 37.815819))
      val time = 27840
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("116378-2"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(origin, 0),
            Modes.BeamMode.CAR,
            asDriver = true
          ),
          StreetVehicle(
            Id.createVehicleId("body-116378-2"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val walkTrip =
        response.itineraries.find(_.tripClassifier == WALK).get.toBeamTrip
      inside(walkTrip) {
        case BeamTrip(legs) =>
          legs.map(_.mode) should contain theSameElementsInOrderAs List(WALK)
          inside(legs.loneElement) {
            case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _, _)) =>
              mode should be(WALK)
              links should be('empty)
          }
      }

      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.costEstimate > 1.0)
      val carTrip = carOption.toBeamTrip
      val actualModesOfCarOption = carTrip.legs.map(_.mode)
      actualModesOfCarOption should contain theSameElementsInOrderAs List(WALK, CAR, WALK)
      assert(carOption.legs(1).unbecomeDriverOnCompletion)
    }

    "respond with a unlimited transfer route having cost 2.75 USD." in {
      val origin = new Coord(549598.9574660371, 4176177.2431860007)
      val destination = new Coord(544417.3891361314, 4177016.733758491)
      val time = 64080
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.costEstimate == 2.75))
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
    }

    "respond with a BART route without transfer having cost 1.95 USD." in {
      val origin = services.geo.wgs2Utm(new Coord(-122.41969, 37.76506)) // 16th St. Mission
      val destination = services.geo.wgs2Utm(new Coord(-122.40686, 37.784992)) // Powell St.
      val time = 51840
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          ),
          StreetVehicle(
            Id.createVehicleId("116378-2"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(origin, 0),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.costEstimate == 1.95))
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
    }

    "respond with Failure(_) to a request with a bad coordinate" in {
      val origin = services.geo.wgs2Utm(new Coord(999999999, 999999999))
      val destination = services.geo.wgs2Utm(new Coord(-122.40686, 37.784992)) // Powell St.
      val time = 51840
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      expectMsgType[Failure]
    }

  }

  def assertMakesSense(trip: BeamTrip): Unit = {
    var time = trip.legs.head.startTime
    trip.legs.foreach(leg => {
      assert(leg.startTime >= time, "Leg starts when previous one finishes.")
      time += leg.duration
    })
  }
}
