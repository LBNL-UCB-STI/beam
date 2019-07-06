package beam.sflight

import akka.actor._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, BeamPath, BeamTrip}
import beam.tags.{ExcludeRegular, Periodic}
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest._

import scala.language.postfixOps

class SfLightRoutePopulationSpec
    extends AbstractSfLightSpec("SfLightRoutePopulationSpec")
    with Inside
    with LoneElement {

  "A router" must {

    "respond with a drive_transit and a walk_transit route for each trip in sflight" taggedAs (Periodic, ExcludeRegular) in {
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
                withTransit = true,
                Vector(
                  StreetVehicle(
                    Id.createVehicleId("116378-2"),
                    Id.create("Car", classOf[BeamVehicleType]),
                    new SpaceTime(origin, 0),
                    CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                    new SpaceTime(new Coord(origin.getX, origin.getY), time),
                    WALK,
                    asDriver = true
                  )
                )
              )
              val response = expectMsgType[RoutingResponse]
              assert(response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT))
              assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
              assert(response.itineraries.filter(_.tripClassifier.isTransit).forall(_.costEstimate > 0))
            })
        })
    }

    "respond with a walk route and usually a car route for each trip in sflight" taggedAs (Periodic, ExcludeRegular) in {
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
                    Id.create("Car", classOf[BeamVehicleType]),
                    new SpaceTime(origin, 0),
                    Modes.BeamMode.CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    Id.create("Car", classOf[BeamVehicleType]),
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
                    case BeamLeg(_, mode, _, BeamPath(_, _, _, _, _, _)) =>
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
                      case BeamLeg(_, mode, _, BeamPath(_, _, _, _, _, _)) =>
                        mode should be(WALK)
                    }
                    inside(legs(1)) {
                      case BeamLeg(_, mode, _, BeamPath(links, _, _, _, _, _)) =>
                        mode should be(CAR)
                        links should not be 'empty
                    }
                    inside(legs(2)) {
                      case BeamLeg(_, mode, _, BeamPath(_, _, _, _, _, _)) =>
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
