package beam.sflight

import akka.actor._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode.{CAR, RIDE_HAIL, WALK}
import beam.router.model.{BeamLeg, BeamPath, BeamTrip}
import beam.router.{BeamRouter, Modes}
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest._

import scala.language.postfixOps

class SfLightRouterSpec extends AbstractSfLightSpec("SfLightRouterSpec") with Inside with LoneElement {
  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = new BeamRouter.Location(583152.4334365112, 4139386.503815964)
      val destination =
        new BeamRouter.Location(572710.8214231567, 4142569.0802786923)
      val time = 25740
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
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

    "respond with a fallback walk route to a RoutingRequest where walking would take approx. 8 hours" in {
      val origin = new BeamRouter.Location(626575.0322098453, 4181202.599243111)
      val destination =
        new BeamRouter.Location(607385.7148858022, 4172426.3760835854)
      val time = 25860
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-56658-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
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
        origin,
        destination,
        time,
        withTransit = false,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-80672-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
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
        origin,
        destination,
        time,
        withTransit = false,
        Vector(
          StreetVehicle(
            Id.createVehicleId("rideHailVehicle-person=17673-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = false
          ),
          StreetVehicle(
            Id.createVehicleId("body-17673-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.WALK,
            asDriver = true
          ),
          StreetVehicle(
            Id.createVehicleId("17673-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == RIDE_HAIL))
      assert(response.itineraries.exists(_.tripClassifier == CAR))

      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      //      assertMakesSense(carOption)
      val actualModesOfCarOption = carOption.toBeamTrip.legs.map(_.mode)
      actualModesOfCarOption should contain theSameElementsInOrderAs List(WALK, CAR, WALK)
    }

    "respond with a walk and a car route for going from downtown SF to Treasure Island" in {
      val origin = geoUtil.wgs2Utm(new Coord(-122.439194, 37.785368))
      val destination = geoUtil.wgs2Utm(new Coord(-122.3712, 37.815819))
      val time = 27840
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        Vector(
          StreetVehicle(
            Id.createVehicleId("116378-2"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, 0),
            Modes.BeamMode.CAR,
            asDriver = true
          ),
          StreetVehicle(
            Id.createVehicleId("rideHailVehicle-person=116378-2"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = false
          ),
          StreetVehicle(
            Id.createVehicleId("body-116378-2"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
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
            case BeamLeg(_, mode, _, BeamPath(links, linkTravelTime, _, _, _, _)) =>
              mode should be(WALK)
              links should be('empty)
          }
      }

    }

    "respond with a walk route and usually a car route for each trip in sflight" in {
      var numFailedCarRoutes = 0
      scenario.getPopulation.getPersons
        .values()
        .forEach(person => {
          val activities = planToVec(person.getSelectedPlan)
          activities
            .sliding(2)
            .foreach(pair => {
              val origin = pair(0).getCoord
              val destination = pair(1).getCoord
              val time = pair(0).getEndTime.toInt
              router ! RoutingRequest(
                origin,
                destination,
                time,
                withTransit = false,
                Vector(
                  StreetVehicle(
                    Id.createVehicleId("116378-2"),
                    BeamVehicleType.defaultCarBeamVehicleType.id,
                    new SpaceTime(origin, 0),
                    Modes.BeamMode.CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    BeamVehicleType.defaultCarBeamVehicleType.id,
                    new SpaceTime(new Coord(origin.getX, origin.getY), time),
                    Modes.BeamMode.WALK,
                    asDriver = true
                  )
                )
              )
              val response = expectMsgType[RoutingResponse]
              assert(response.itineraries.exists(_.tripClassifier == WALK))

              val walkTrip = response.itineraries
                .find(_.tripClassifier == WALK)
                .get
                .toBeamTrip
              inside(walkTrip) {
                case BeamTrip(legs) =>
                  legs.map(_.mode) should contain theSameElementsInOrderAs List(WALK)
                  inside(legs.loneElement) {
                    case BeamLeg(_, mode, _, BeamPath(_, linkTravelTime, _, _, _, _)) =>
                      mode should be(WALK)
                  }
              }

              if (response.itineraries.exists(_.tripClassifier == CAR)) {
                val carTrip = response.itineraries
                  .find(_.tripClassifier == CAR)
                  .get
                  .toBeamTrip
                assertMakesSense(carTrip)
                inside(carTrip) {
                  case BeamTrip(legs) =>
                    legs should have size 3
                    inside(legs(0)) {
                      case BeamLeg(_, mode, _, BeamPath(_, linkTravelTime, _, _, _, _)) =>
                        mode should be(WALK)
                    }
                    inside(legs(1)) {
                      case BeamLeg(_, mode, _, BeamPath(links, linkTravelTime, _, _, _, _)) =>
                        mode should be(CAR)
                        links should not be 'empty
                    }
                    inside(legs(2)) {
                      case BeamLeg(_, mode, _, BeamPath(_, linkTravelTime, _, _, _, _)) =>
                        mode should be(WALK)
                    }
                }
              } else {
                numFailedCarRoutes = numFailedCarRoutes + 1
              }
            })
        })
      // Sometimes car routes fail, but should be very rare
      assert(numFailedCarRoutes < 7)
    }
  }

  def assertMakesSense(trip: BeamTrip): Unit = {
    var time = trip.legs.head.startTime
    trip.legs.foreach(leg => {
      assert(leg.startTime == time, "Leg starts when previous one finishes.")
      time += leg.duration
    })
  }
}
